package top.wcpe.mc.mpmt.platform.neoforge.acceptance.client;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.RunStepPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.NeoForgeAcceptanceControlChannel;

/**
 * realserver 验收客户端伴侣（仅客户端环境，ADR-0014）：程序化客户端连入真实服后，逐 tick 服务服务端下发的
 * {@link RunStepPacket}——经 {@link ClientVerifierRegistry} 找验证器轮询、判定后回 {@link StepResultPacket}。
 *
 * <p><b>仅客户端</b>（{@link OnlyIn}(Dist.CLIENT)）：引用 {@link Minecraft} 等客户端专有类型，服务端不得加载。
 * 加载到主菜单后<b>程序化连入</b>验收服务端（地址取 {@code -Dmpmt.acceptance.server}，默认 {@code 127.0.0.1}）；
 * 进世界后逐 tick 上报 {@link ClientReadyPacket}（一次）。
 *
 * <p><b>SimpleChannel 收发</b>：收包经验收控制通道 {@link NeoForgeAcceptanceControlChannel#setClientReceiver}
 * 注入的处理器切客户端线程分发到 {@link #onClientControl}；出站经
 * {@link NeoForgeAcceptanceControlChannel#sendToServer} 经通道发给服务端。NeoForge↔NeoForge 走 FML 握手、通道可用。
 *
 * <p><b>线程</b>：入站经通道切客户端线程入队、服务在客户端 tick 线程出队（队列并发安全；验证器只在 tick 线程读客户端态，
 * ADR-0013）。
 */
@OnlyIn(Dist.CLIENT)
public final class NeoForgeAcceptanceClientCompanion {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");
    private static final int STEP_TIMEOUT_TICKS = 200;

    private final ClientVerifierRegistry verifiers = new ClientVerifierRegistry();
    private final Queue<RunStepPacket> inbound = new ConcurrentLinkedQueue<>();

    /** 登入仅上报一次 ClientReady（进世界后 tick 去重）。 */
    private final AtomicBoolean readyReported = new AtomicBoolean(false);

    /** 程序化连入只发起一次（dev 客户端 quickPlay 不可靠，故由伴侣到主菜单后自连）。 */
    private final AtomicBoolean connectAttempted = new AtomicBoolean(false);

    /** 验收控制通道（出站经其 sendToServer 发、入站经其 setClientReceiver 收）；register 时取得。 */
    private NeoForgeAcceptanceControlChannel control;

    private RunStepPacket active;
    private int ticksInStep;

    /**
     * 注册控制通道接收 + 客户端事件总线（逐 tick 服务 + 进世界上报）。
     *
     * @param control 验收控制通道（{@code mpmt-test:acceptance}），由服务端验收通道构造期注册并传入
     */
    public void register(NeoForgeAcceptanceControlChannel control) {
        this.control = Objects.requireNonNull(control, "control 不能为空");
        // 向控制通道注入客户端收包处理器：SimpleChannel 切客户端线程分发 onClientControl
        control.setClientReceiver(this::onClientControl);
        // 客户端 tick 用 NeoForge 事件总线
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("realserver NeoForge 验收客户端伴侣已注册");
    }

    /** 客户端 tick（END 相）：逐 tick 服务一个待验证步骤（含进世界后首次上报 ClientReady）。 */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        tryAutoConnect(client);
        serveTick(client);
    }

    /**
     * 到主菜单后程序化连入验收服务端（只发起一次）。dev 客户端不可靠地处理 {@code --quickPlayMultiplayer}，
     * 故由伴侣自连，更稳。地址取系统属性 {@code mpmt.acceptance.server}（默认 {@code 127.0.0.1}）。
     */
    private void tryAutoConnect(Minecraft client) {
        if (client.level != null) {
            return; // 已在世界
        }
        // 客户端开始 tick 且有屏幕即可连——不等 TitleScreen：mod 资源包警告屏会一直挡在 TitleScreen 之前，
        // 而 startConnecting 直接替换当前屏幕，故从<b>任意</b>非世界屏幕（含警告屏）发起连接，绕过警告屏阻塞。
        if (client.screen == null || client.screen instanceof ConnectScreen) {
            return; // 还在加载（无屏幕）或已在连接
        }
        if (!connectAttempted.compareAndSet(false, true)) {
            return; // 只发起一次
        }
        String address = System.getProperty("mpmt.acceptance.server", "127.0.0.1");
        LOGGER.info("realserver NeoForge 验收伴侣：程序化连入 {}", address);
        // 1.20.2 该构造为 (name, ip, Type)；OTHER=直连普通服
        ServerData data = new ServerData("mpmt-acceptance", address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(
                client.screen, client, ServerAddress.parseString(address), data, false);
    }

    /** 收到服务端下发的控制字节（已在客户端线程，由通道切入）：解码 RunStep 入队。 */
    private void onClientControl(byte[] data) {
        try {
            AcceptanceControlPacket packet = AcceptanceControlCodec.decode(data);
            if (packet instanceof RunStepPacket) {
                inbound.add((RunStepPacket) packet);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("丢弃非法验收控制包：{}", e.getMessage());
        }
    }

    private void serveTick(Minecraft client) {
        if (client.player == null) {
            return; // 未进世界
        }
        // 修严重 bug：验收 gametest 客户端进世界后 MC 默认抓取鼠标光标（锁定到游戏窗口），会把用户鼠标
        // 拉进窗口并占用焦点。验收全自动、无需用户在游戏内操作，故每 tick 释放被抓取的光标，杜绝抢占用户鼠标。
        if (client.mouseHandler.isMouseGrabbed()) {
            client.mouseHandler.releaseMouse();
        }
        // 进世界后上报一次 ClientReady（从 tick 上报：玩家已完全在世界、连接处于 PLAY，比 LoggingIn 事件更稳）
        if (readyReported.compareAndSet(false, true)) {
            LOGGER.info("已进世界，上报 ClientReady");
            send(new ClientReadyPacket(AcceptanceControlCodec.PROTOCOL_VERSION));
        }
        if (active == null) {
            active = inbound.poll();
            ticksInStep = 0;
            if (active == null) {
                return; // 无待服务步骤
            }
        }
        VerifyOutcome outcome = evaluate(client);
        if (outcome == null) {
            ticksInStep++;
            if (ticksInStep < STEP_TIMEOUT_TICKS) {
                return; // 继续轮询
            }
            outcome = VerifyOutcome.fail("客户端步骤超时 " + STEP_TIMEOUT_TICKS + " tick");
        }
        send(
                new StepResultPacket(
                        active.getScenarioId(),
                        active.getStepId(),
                        active.getSeq(),
                        outcome.status(),
                        outcome.resultJson(),
                        outcome.message()));
        active = null;
    }

    private VerifyOutcome evaluate(Minecraft client) {
        ClientVerifier verifier = verifiers.find(active.getScenarioId());
        if (verifier == null) {
            return VerifyOutcome.error("无客户端验证器：scenarioId=" + active.getScenarioId());
        }
        try {
            return verifier.poll(
                    new VerifyStep(active.getStepId(), active.getParamsJson(), ticksInStep),
                    new RealServerClientContext(client));
        } catch (RuntimeException e) {
            return VerifyOutcome.error("验证器异常：" + e.getMessage());
        }
    }

    /** 出站：经控制通道把控制字节发给服务端（SimpleChannel，与服务端收包对齐）。 */
    private void send(AcceptanceControlPacket packet) {
        NeoForgeAcceptanceControlChannel current = control;
        if (current == null || Minecraft.getInstance().getConnection() == null) {
            return; // 未注册 / 尚未连入（正常流程不会发生）
        }
        current.sendToServer(AcceptanceControlCodec.encode(packet));
    }
}
