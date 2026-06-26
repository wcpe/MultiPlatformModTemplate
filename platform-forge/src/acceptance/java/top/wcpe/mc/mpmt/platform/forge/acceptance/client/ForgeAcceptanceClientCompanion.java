package top.wcpe.mc.mpmt.platform.forge.acceptance.client;

import io.netty.buffer.Unpooled;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.event.EventNetworkChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.RunStepPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.platform.forge.acceptance.ForgeAcceptanceControlChannelId;

/**
 * realserver 验收客户端伴侣（仅客户端环境，ADR-0014）：程序化客户端连入真实 Forge 服后，逐 tick 服务服务端
 * 下发的 {@link RunStepPacket}——经 {@link ClientVerifierRegistry} 找验证器轮询、判定后回 {@link StepResultPacket}。
 *
 * <p><b>仅客户端</b>（{@link OnlyIn}(Dist.CLIENT)）：引用 {@link Minecraft} 等客户端专有类型，服务端不得加载。
 * 加载到主菜单后<b>程序化连入</b>验收服务端（Forge dev 客户端不可靠处理 {@code --quickPlayMultiplayer}，故伴侣
 * 自连，地址取 {@code -Dmpmt.acceptance.server}，默认 {@code 127.0.0.1}）；客户端登入
 * （{@link ClientPlayerNetworkEvent.LoggingIn}）即上报 {@link ClientReadyPacket}。
 *
 * <p><b>通道复用</b>：验收控制通道 {@code mpmt-test:acceptance} 由服务端 {@code ForgeAcceptanceControlChannel}
 * 在构造期注册（同名通道只能注册一次），本伴侣只在<b>同一</b> {@link EventNetworkChannel} 上 {@code addListener}
 * 收 {@link NetworkEvent.ClientCustomPayloadEvent}（裸字节）。
 *
 * <p><b>线程</b>：入站在网络线程入队、服务在客户端 tick 线程出队（队列并发安全；验证器只在 tick 线程读客户端态，
 * ADR-0013）。出站经裸 {@link ServerboundCustomPayloadPacket} 由 {@link ClientPacketListener#send} 发出，与服务端
 * {@code ServerCustomPayloadEvent} 接收对齐。
 */
@OnlyIn(Dist.CLIENT)
public final class ForgeAcceptanceClientCompanion {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");
    private static final int STEP_TIMEOUT_TICKS = 200;

    private final ClientVerifierRegistry verifiers = new ClientVerifierRegistry();
    private final Queue<RunStepPacket> inbound = new ConcurrentLinkedQueue<>();

    /** 登入仅上报一次 ClientReady（LoggingIn 事件去重）。 */
    private final AtomicBoolean readyReported = new AtomicBoolean(false);

    /** 程序化连入只发起一次（Forge dev 客户端 quickPlay 不可靠，故由伴侣到主菜单后自连）。 */
    private final AtomicBoolean connectAttempted = new AtomicBoolean(false);

    private RunStepPacket active;
    private int ticksInStep;

    /**
     * 注册控制通道接收 + 客户端事件总线（登入上报 + 逐 tick 服务）。
     *
     * @param channel 验收控制通道（{@code mpmt-test:acceptance}）实例，由服务端验收通道构造期注册并传入
     */
    public void register(EventNetworkChannel channel) {
        // 在同一验收通道上追加客户端入站监听（不重注册通道）
        channel.addListener(this::onClientPayload);
        // 客户端 tick / 登入用 Forge 事件总线
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("realserver Forge 验收客户端伴侣已注册");
    }

    /** 客户端登入：上报就绪（去重，避免重连重复上报）。 */
    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (readyReported.compareAndSet(false, true)) {
            send(new ClientReadyPacket(AcceptanceControlCodec.PROTOCOL_VERSION));
        }
    }

    /** 客户端 tick（END 相）：逐 tick 服务一个待验证步骤。 */
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
     * 到主菜单后程序化连入验收服务端（只发起一次）。Forge dev 客户端不可靠地处理 {@code --quickPlayMultiplayer}，
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
        LOGGER.info("realserver Forge 验收伴侣：程序化连入 {}", address);
        // 1.20.1 该构造为 (name, ip, isLan)；isLan=false（直连普通服）
        ServerData data = new ServerData("mpmt-acceptance", address, false);
        ConnectScreen.startConnecting(
                client.screen, client, ServerAddress.parseString(address), data, false);
    }

    /** 收到服务端下发的裸控制 payload：解码 RunStep 入队（网络线程）。 */
    private void onClientPayload(NetworkEvent.ClientCustomPayloadEvent event) {
        NetworkEvent.Context ctx = event.getSource().get();
        byte[] data = readAll(event.getPayload());
        try {
            AcceptanceControlPacket packet = AcceptanceControlCodec.decode(data);
            if (packet instanceof RunStepPacket) {
                inbound.add((RunStepPacket) packet);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("丢弃非法验收控制包：{}", e.getMessage());
        }
        ctx.setPacketHandled(true);
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

    /** 出站：裸 {@link ServerboundCustomPayloadPacket} 发给服务端（与服务端 ServerCustomPayloadEvent 对齐）。 */
    private static void send(AcceptanceControlPacket packet) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return; // 尚未连入（正常流程不会发生：发送都在登入 / tick 之后）
        }
        byte[] data = AcceptanceControlCodec.encode(packet);
        FriendlyByteBuf buf =
                new FriendlyByteBuf(Unpooled.buffer(data.length == 0 ? 1 : data.length));
        buf.writeBytes(data);
        connection.send(
                new ServerboundCustomPayloadPacket(ForgeAcceptanceControlChannelId.CHANNEL, buf));
    }

    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }
}
