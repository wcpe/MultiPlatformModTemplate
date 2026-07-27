package top.wcpe.mc.mpmt.platform.fabric.gametest.client;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.RunStepPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReportV2Factory;
import top.wcpe.mc.mpmt.acceptance.report.JavaRuntimeInfo;
import top.wcpe.mc.mpmt.platform.fabric.gametest.AcceptanceControlChannelId;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricClientNetwork;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricNetworkBindings;

/**
 * realserver 验收客户端伴侣：经选中 L4 适配器收发控制通道，避免绑死 1.20 网络 API。
 *
 * <p>26.2 上 {@code --quickPlayMultiplayer} 常被无障碍引导/资源包屏挡住而不连服，
 * 故在任意非世界屏幕上程序化 {@link ConnectScreen#startConnecting} 兜底（对齐 Forge 1.21 伴侣）。
 */
@Environment(EnvType.CLIENT)
public final class AcceptanceClientCompanion {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");
    /** 与 DEFAULT_CLIENT_STEP_TIMEOUT_MS=30s 对齐（20 tps × 30s），避免 HUD 冷启动略慢即误杀 */
    private static final int STEP_TIMEOUT_TICKS = 600;
    /** 主菜单出现后再等若干 tick，避开资源包/引导屏首帧 */
    private static final int AUTO_CONNECT_DELAY_TICKS = 40;
    private static final String SERVER_PROPERTY = "mpmt.acceptance.server";
    private static final String DEFAULT_SERVER = "127.0.0.1:25571";

    private final ClientVerifierRegistry verifiers = new ClientVerifierRegistry();
    private final Queue<RunStepPacket> inbound = new ConcurrentLinkedQueue<>();
    private final FabricClientNetwork network =
            FabricNetworkBindings.selectedAdapter()
                    .clientNetwork(AcceptanceControlChannelId.CHANNEL);
    private final AtomicBoolean connectAttempted = new AtomicBoolean(false);

    private RunStepPacket active;
    private int ticksInStep;
    private int ticksSinceMenu;

    /** 注册控制通道接收 + 进服上报就绪 + 逐 tick 服务（含程序化自连兜底）。 */
    public void register() {
        network.registerReceiver(this::receive);
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    network.registerReceiver(this::receive);
                    // 延后 2 tick 再报就绪：让产品 mod 的 JOIN + 握手 tick 先挂上 S2C 收包/HUD
                    client.execute(() -> client.execute(this::sendClientReady));
                });
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        LOGGER.info("realserver 验收客户端伴侣已注册（含程序化自连兜底）");
    }

    private void onClientTick(Minecraft client) {
        tryAutoConnect(client);
        serveTick(client);
    }

    /**
     * 到任意非世界屏幕后程序化连入验收服（只发起一次）。
     *
     * <p>不等纯 TitleScreen：AccessibilityOnboarding / 资源包警告会挡住 quickPlay；
     * {@link ConnectScreen#startConnecting} 可直接替换当前屏。
     */
    private void tryAutoConnect(Minecraft client) {
        if (client.level != null || client.getConnection() != null) {
            return;
        }
        Screen screen = client.gui.screen();
        if (screen == null || screen instanceof ConnectScreen) {
            return;
        }
        ticksSinceMenu++;
        if (ticksSinceMenu < AUTO_CONNECT_DELAY_TICKS) {
            return;
        }
        if (!connectAttempted.compareAndSet(false, true)) {
            return;
        }
        String address = System.getProperty(SERVER_PROPERTY, DEFAULT_SERVER);
        if (address == null || address.trim().isEmpty()) {
            address = DEFAULT_SERVER;
        }
        if (!address.contains(":")) {
            address = address + ":25571";
        }
        LOGGER.info(
                "quickPlay 兜底：自动连接 {}（screen={}）",
                address,
                screen.getClass().getSimpleName());
        try {
            ServerAddress serverAddress = ServerAddress.parseString(address);
            ServerData data = new ServerData("mpmt-acceptance", address, ServerData.Type.OTHER);
            Screen parent = client.gui.screen() != null ? client.gui.screen() : new TitleScreen();
            ConnectScreen.startConnecting(parent, client, serverAddress, data, false, (TransferState) null);
        } catch (RuntimeException error) {
            connectAttempted.set(false);
            LOGGER.warn("自动连服失败，将重试：{}", error.getMessage());
        }
    }

    private void receive(byte[] data) {
        try {
            AcceptanceControlPacket packet = AcceptanceControlCodec.decode(data);
            if (packet instanceof RunStepPacket) {
                inbound.add((RunStepPacket) packet);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("丢弃非法验收控制包：{}", e.getMessage());
        }
    }

    private void sendClientReady() {
        try {
            JavaRuntimeInfo javaInfo =
                    AcceptanceReportV2Factory.currentJava(
                            System.getProperty("mpmt.acceptance.javaExecutable"));
            send(
                    new ClientReadyPacket(
                            AcceptanceControlCodec.PROTOCOL_VERSION,
                            javaInfo.getMajor(),
                            javaInfo.getExecutable()));
        } catch (RuntimeException e) {
            LOGGER.error("无法上报客户端 Java 运行身份：{}", e.getMessage());
        }
    }

    private void serveTick(Minecraft client) {
        if (client.player == null) {
            return;
        }
        if (client.mouseHandler.isMouseGrabbed()) {
            client.mouseHandler.releaseMouse();
        }
        if (active == null) {
            active = inbound.poll();
            ticksInStep = 0;
            if (active == null) {
                return;
            }
        }
        VerifyOutcome outcome = evaluate(client);
        if (outcome == null) {
            ticksInStep++;
            if (ticksInStep < STEP_TIMEOUT_TICKS) {
                return;
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

    private void send(AcceptanceControlPacket packet) {
        network.send(AcceptanceControlCodec.encode(packet));
    }
}
