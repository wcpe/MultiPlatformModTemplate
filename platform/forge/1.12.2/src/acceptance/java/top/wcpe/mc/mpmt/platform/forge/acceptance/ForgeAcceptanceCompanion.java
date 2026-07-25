package top.wcpe.mc.mpmt.platform.forge.acceptance;

import java.io.File;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.RunStepPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeClientTransportPort;

/** 1.12.2 realserver 验收客户端伴侣。 */
final class ForgeAcceptanceCompanion {

    private static final Logger LOGGER = LogManager.getLogger(MpmtForgeAcceptanceMod.MOD_ID);
    private static final int STEP_TIMEOUT_TICKS = 200;
    private static final String JAVA_EXECUTABLE_PROPERTY = "mpmt.acceptance.javaExecutable";
    private static final String SERVER_PROPERTY = "mpmt.acceptance.server";
    /** 默认对齐 CatServer R5 宿主端口。 */
    private static final String DEFAULT_SERVER = "127.0.0.1:25568";

    private final ForgeClientTransportPort transport;
    private final ForgeVerificationRegistry verifiers = new ForgeVerificationRegistry();
    private final Queue<RunStepPacket> inbound = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean connectAttempted = new AtomicBoolean(false);

    private RunStepPacket active;
    private int ticksInStep;
    private boolean readyReported;

    ForgeAcceptanceCompanion(ForgeClientTransportPort transport) {
        this.transport = transport;
        transport.onReceive((connection, data) -> receive(data));
    }

    @SubscribeEvent
    public void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        readyReported = false;
        LOGGER.info("客户端已进入服务端，等待玩家进入世界后上报验收身份");
    }

    @SubscribeEvent
    public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        inbound.clear();
        active = null;
        ticksInStep = 0;
        readyReported = false;
        verifiers.clear();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft client = Minecraft.getMinecraft();
        // 主菜单阶段即可自连，不得等 player 就绪（否则永远停在主菜单）
        tryAutoConnect(client);
        if (client.player == null) {
            return;
        }
        reportReadyIfNeeded();
        if (active == null) {
            active = inbound.poll();
            ticksInStep = 0;
            if (active == null) {
                return;
            }
        }
        ForgeVerifyOutcome outcome = evaluate();
        if (outcome == null) {
            ticksInStep++;
            if (ticksInStep < STEP_TIMEOUT_TICKS) {
                return;
            }
            outcome = ForgeVerifyOutcome.fail("客户端步骤超时 " + STEP_TIMEOUT_TICKS + " tick");
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

    /**
     * 主菜单（或任意非世界屏幕）后程序化连入验收服，只发起一次。
     *
     * <p>地址取 {@code -Dmpmt.acceptance.server}，默认 {@code 127.0.0.1:25568}（CatServer R5）。
     */
    private void tryAutoConnect(Minecraft client) {
        if (client.world != null || client.player != null) {
            return;
        }
        GuiScreen screen = client.currentScreen;
        if (screen == null || screen instanceof GuiConnecting) {
            return;
        }
        if (!connectAttempted.compareAndSet(false, true)) {
            return;
        }
        String address = System.getProperty(SERVER_PROPERTY, DEFAULT_SERVER);
        LOGGER.info("realserver Forge 1.12.2 验收伴侣：程序化连入 {}", address);
        ServerData data = new ServerData("mpmt-acceptance", address, false);
        client.displayGuiScreen(new GuiConnecting(screen, client, data));
    }


    private void reportReadyIfNeeded() {
        if (readyReported) {
            return;
        }
        int major = javaMajor();
        String executable = javaExecutable();
        send(new ClientReadyPacket(AcceptanceControlCodec.PROTOCOL_VERSION, major, executable));
        readyReported = true;
        LOGGER.info("验收控制协议 v2 已上报客户端 Java {}：{}", major, executable);
    }

    private ForgeVerifyOutcome evaluate() {
        try {
            return verifiers.poll(active);
        } catch (RuntimeException exception) {
            return ForgeVerifyOutcome.error("验证器异常：" + exception.getMessage());
        }
    }

    private void receive(byte[] data) {
        try {
            AcceptanceControlPacket packet = AcceptanceControlCodec.decode(data);
            if (packet instanceof RunStepPacket) {
                inbound.add((RunStepPacket) packet);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("丢弃非法验收控制包：{}", exception.getMessage());
        }
    }

    private void send(AcceptanceControlPacket packet) {
        transport.send(AcceptanceControlCodec.encode(packet));
    }

    private static int javaMajor() {
        String specification = System.getProperty("java.specification.version", "");
        if (specification.isEmpty()) {
            throw new IllegalStateException("无法读取 java.specification.version");
        }
        String major = specification.startsWith("1.") ? specification.substring(2) : specification;
        int separator = major.indexOf('.');
        return Integer.parseInt(separator < 0 ? major : major.substring(0, separator));
    }

    private static String javaExecutable() {
        String configured = System.getProperty(JAVA_EXECUTABLE_PROPERTY, "").trim();
        if (!configured.isEmpty()) {
            if (!new File(configured).isFile()) {
                throw new IllegalStateException("指定的 Java 可执行文件不存在：" + configured);
            }
            return configured;
        }
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        File fromEnvironment = javaExecutable(System.getenv("JAVA_HOME"), executable);
        if (fromEnvironment != null) {
            return fromEnvironment.getAbsolutePath();
        }
        File runtimeHome = new File(System.getProperty("java.home"));
        File fromRuntime = javaExecutable(runtimeHome.getAbsolutePath(), executable);
        if (fromRuntime != null) {
            return fromRuntime.getAbsolutePath();
        }
        File fromParent = javaExecutable(runtimeHome.getParent(), executable);
        if (fromParent != null) {
            return fromParent.getAbsolutePath();
        }
        throw new IllegalStateException("无法定位当前 Java 可执行文件");
    }

    private static File javaExecutable(String javaHome, String executable) {
        if (javaHome == null || javaHome.trim().isEmpty()) {
            return null;
        }
        File candidate = new File(new File(javaHome, "bin"), executable);
        return candidate.isFile() ? candidate : null;
    }
}
