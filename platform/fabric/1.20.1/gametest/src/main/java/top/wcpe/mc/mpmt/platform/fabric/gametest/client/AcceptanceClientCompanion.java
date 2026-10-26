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
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
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
 */
@Environment(EnvType.CLIENT)
public final class AcceptanceClientCompanion {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");
    /** 与 DEFAULT_CLIENT_STEP_TIMEOUT_MS=30s 对齐（20 tps × 30s），避免 HUD 冷启动略慢即误杀 */
    private static final int STEP_TIMEOUT_TICKS = 600;

    private final ClientVerifierRegistry verifiers = new ClientVerifierRegistry();
    private final Queue<RunStepPacket> inbound = new ConcurrentLinkedQueue<>();
    private final FabricClientNetwork network =
            FabricNetworkBindings.selectedAdapter()
                    .clientNetwork(AcceptanceControlChannelId.CHANNEL);

    private RunStepPacket active;
    private int ticksInStep;
    /** 单机轨只创建一次世界，避免每 tick 重复触发。 */
    private final AtomicBoolean singleplayerStartAttempted = new AtomicBoolean(false);

    /** 注册控制通道接收 + 进服上报就绪 + 逐 tick 服务。 */
    public void register() {
        network.registerReceiver(this::receive);
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    network.registerReceiver(this::receive);
                    // 延后 2 tick 再报就绪：让产品 mod 的 JOIN + 握手 tick 先挂上 S2C 收包/HUD
                    client.execute(() -> client.execute(this::sendClientReady));
                });
        ClientTickEvents.END_CLIENT_TICK.register(this::serveTick);
        // 单机轨（-Dmpmt.acceptance.singleplayer=true）：无外部服务端可连，
        // 到主菜单后创建单人世界，由客户端内置服务端承载验收场景。
        if (Boolean.getBoolean("mpmt.acceptance.singleplayer")) {
            ClientTickEvents.END_CLIENT_TICK.register(this::maybeStartSingleplayerWorld);
        }
        LOGGER.info("realserver 验收客户端伴侣已注册");
    }

    /** 到主菜单且尚未进入世界时创建单人世界（只发起一次）。 */
    private void maybeStartSingleplayerWorld(Minecraft client) {
        if (client.level != null) {
            return; // 已在世界
        }
        if (client.screen == null || client.screen instanceof ConnectScreen) {
            return; // 还在加载或正在连接
        }
        if (!singleplayerStartAttempted.compareAndSet(false, true)) {
            return;
        }
        String world = System.getProperty("mpmt.acceptance.singleplayer.world", "mpmt-acceptance");
        LOGGER.info("realserver 验收伴侣：单机轨，创建单人世界 {}", world);
        client.execute(
                () ->
                        client.createWorldOpenFlows()
                                .createFreshLevel(
                                        world,
                                        new LevelSettings(
                                                world,
                                                GameType.SURVIVAL,
                                                false,
                                                Difficulty.NORMAL,
                                                true,
                                                new GameRules(),
                                                WorldDataConfiguration.DEFAULT),
                                        new WorldOptions(
                                                WorldOptions.randomSeed(), true, false),
                                        WorldPresets::createNormalWorldDimensions));
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
