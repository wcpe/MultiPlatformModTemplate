package top.wcpe.mc.mpmt.platform.forge.modern.acceptance;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.RunStepPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepStatus;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReportV2Factory;
import top.wcpe.mc.mpmt.acceptance.report.JavaRuntimeInfo;
import top.wcpe.mc.mpmt.core.client.ClientNetworkFeature;
import top.wcpe.mc.mpmt.platform.forge.modern.client.ForgeClientEvents;
import top.wcpe.mc.mpmt.platform.forge.modern.client.ForgeHudSnapshot;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;

/**
 * Forge 客户端验收伴侣：程序化连服兜底 + 控制通道步骤断言。
 *
 * <p>1.21 FG dev 的 {@code --quickPlayMultiplayer} 不可靠（常被 AccessibilityOnboarding 挡住），
 * 故在任意非世界屏幕上发起一次自连（与 1.20.1 Forge 伴侣一致）。
 *
 * <p>静态可变状态只由客户端 tick 事件线程（单线程）读写，故 @SuppressWarnings 关闭
 * NonThreadSafeSingleton（与 Forge 26.2 伴侣同口径）。
 */
@Mod.EventBusSubscriber(modid = MpmtForge121AcceptanceMod.MOD_ID, value = Dist.CLIENT)
@SuppressWarnings("PMD.NonThreadSafeSingleton")
public final class ForgeAcceptanceClientCompanion {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");
    /** 普通步骤 200 tick；client-hud 需等演示包过后再收验收 ACTIONBAR，放宽至 400。 */
    private static final int STEP_TIMEOUT_TICKS = 200;
    private static final int HUD_STEP_TIMEOUT_TICKS = 400;
    private static final int AUTO_CONNECT_DELAY_TICKS = 40;
    private static final long PING_NONCE = 20260718L;
    private static final String EXPECTED_HUD_TEXT = "验收HUD";
    private static final String SERVER_PROPERTY = "mpmt.acceptance.server";
    private static final Queue<RunStepPacket> INBOUND = new ConcurrentLinkedQueue<>();

    private static final AtomicBoolean CONNECT_ATTEMPTED = new AtomicBoolean(false);

    private static Connection connection;
    private static RunStepPacket active;
    private static int ticksInStep;
    private static int ticksSinceMenu;
    private static boolean pingSent;
    private static volatile boolean pongReceived;
    /** 就绪只上报一次；延到玩家就位后再发，避免与产品握手演示 HUD 抢时序。 */
    private static volatile boolean clientReadySent;

    private ForgeAcceptanceClientCompanion() {
        // 事件订阅类不实例化
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        connection = event.getConnection();
        MpmtForge121AcceptanceMod.control().registerClientReceiver(
                ForgeAcceptanceClientCompanion::receive);
        // 不在此处立即上报就绪：LoggingIn 时产品握手尚未下发 TITLE/ACTIONBAR/TOAST/CHAT 演示包，
        // 若此刻就绪，首个场景（按 suite/id 字母序为 client-hud）会先发验收 ACTIONBAR、
        // 随后被演示 ACTIONBAR 覆盖，导致 400 tick 超时。改为 tick 内 player 就位后再上报（对齐 26.2）。
        LOGGER.info("验收客户端伴侣已连接控制通道：平台=Forge");
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        tryAutoConnect(minecraft);
        if (minecraft.player == null || connection == null) {
            return;
        }
        // 玩家真正就位后才上报就绪，确保产品握手的演示 HUD 已先行下发（见 onLoggingIn 注释）
        if (!clientReadySent) {
            clientReadySent = true;
            sendClientReady();
        }
        if (minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.releaseMouse();
        }
        if (active == null) {
            active = INBOUND.poll();
            ticksInStep = 0;
            pingSent = false;
            pongReceived = false;
            if (active == null) {
                return;
            }
        }
        Outcome outcome = evaluate();
        int timeoutTicks =
                active != null && "client-hud".equals(active.getScenarioId())
                        ? HUD_STEP_TIMEOUT_TICKS
                        : STEP_TIMEOUT_TICKS;
        if (outcome == null && ++ticksInStep < timeoutTicks) {
            return;
        }
        if (outcome == null) {
            // HUD 超时附带最近动作栏快照，便于区分「未收到 ACTIONBAR」与「收到但文本不符」
            if (active != null && "client-hud".equals(active.getScenarioId())) {
                ForgeHudSnapshot snap = ForgeClientEvents.session().actionBarSnapshot();
                outcome =
                        Outcome.fail(
                                "客户端 HUD 步骤超时 "
                                        + timeoutTicks
                                        + " tick 最近动作栏="
                                        + (snap == null
                                                ? "null"
                                                : ("kind=" + snap.kind() + " text=" + snap.text())));
            } else {
                outcome = Outcome.fail("客户端步骤超时 " + timeoutTicks + " tick");
            }
        }
        send(new StepResultPacket(
                active.getScenarioId(),
                active.getStepId(),
                active.getSeq(),
                outcome.status,
                outcome.resultJson,
                outcome.message));
        active = null;
    }

    /**
     * 主菜单/引导屏出现后程序化连服（只发起一次）。
     *
     * <p>不等纯 TitleScreen：AccessibilityOnboarding 等会挡住 quickPlay；startConnecting 可直接替换当前屏。
     */
    private static void tryAutoConnect(Minecraft client) {
        if (client.level != null || client.getConnection() != null) {
            return;
        }
        Screen screen = client.screen;
        if (screen == null || screen instanceof ConnectScreen) {
            return;
        }
        ticksSinceMenu++;
        if (ticksSinceMenu < AUTO_CONNECT_DELAY_TICKS) {
            return;
        }
        if (!CONNECT_ATTEMPTED.compareAndSet(false, true)) {
            return;
        }
        // 单机轨（-Dmpmt.acceptance.singleplayer=true）：不连外部服，改为创建单人世界，
        // 由客户端内置服务端承载验收场景；此时服务端与客户端同进程，控制通道直接可用。
        if (Boolean.getBoolean("mpmt.acceptance.singleplayer")) {
            String world = System.getProperty("mpmt.acceptance.singleplayer.world", "mpmt-acceptance");
            LOGGER.info("realserver Forge 验收伴侣：单机轨，创建单人世界 {}", world);
            client.execute(
                    () ->
                            client.createWorldOpenFlows()
                                    .createFreshLevel(
                                            world,
                                            new net.minecraft.world.level.LevelSettings(
                                                    world,
                                                    net.minecraft.world.level.GameType.SURVIVAL,
                                                    false,
                                                    net.minecraft.world.Difficulty.NORMAL,
                                                    true,
                                                    new net.minecraft.world.level.GameRules(),
                                                    net.minecraft.world.level.WorldDataConfiguration
                                                            .DEFAULT),
                                            new net.minecraft.world.level.levelgen.WorldOptions(
                                                    net.minecraft.world.level.levelgen.WorldOptions
                                                            .randomSeed(),
                                                    true,
                                                    false),
                                            net.minecraft.world.level.levelgen.presets.WorldPresets
                                                    ::createNormalWorldDimensions,
                                            client.screen));
            return;
        }
        String address = System.getProperty(SERVER_PROPERTY, "127.0.0.1");
        if (address == null || address.trim().isEmpty()) {
            address = "127.0.0.1";
        }
        if (!address.contains(":")) {
            address = address + ":25565";
        }
        LOGGER.info(
                "quickPlay 兜底：自动连接 {}（screen={}）",
                address,
                screen.getClass().getSimpleName());
        try {
            connectCompat(client, address);
        } catch (RuntimeException error) {
            CONNECT_ATTEMPTED.set(false);
            LOGGER.warn("自动连服失败，将重试：{}", error.getMessage());
        }
    }

    private static void connectCompat(Minecraft client, String address) {
        ServerAddress serverAddress = ServerAddress.parseString(address);
        Object serverData = createServerData(address);
        Screen parent = client.screen != null ? client.screen : new TitleScreen();
        try {
            // 1.21.1: startConnecting(..., TransferState)
            ConnectScreen.class
                    .getMethod(
                            "startConnecting",
                            Screen.class,
                            Minecraft.class,
                            ServerAddress.class,
                            ServerData.class,
                            boolean.class,
                            Class.forName("net.minecraft.client.multiplayer.TransferState"))
                    .invoke(null, parent, client, serverAddress, serverData, false, null);
            return;
        } catch (ReflectiveOperationException ignored) {
            // 回退 1.20 五参数
        }
        try {
            ConnectScreen.class
                    .getMethod(
                            "startConnecting",
                            Screen.class,
                            Minecraft.class,
                            ServerAddress.class,
                            ServerData.class,
                            boolean.class)
                    .invoke(null, parent, client, serverAddress, serverData, false);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法调用 ConnectScreen.startConnecting", error);
        }
    }

    @SuppressWarnings("unchecked") private static Object createServerData(String address) { // 枚举类型运行期才可得，捕获类型必然触发未经检查的调用
        try {
            Class<?> typeClass = Class.forName("net.minecraft.client.multiplayer.ServerData$Type");
            Object other = Enum.valueOf(typeClass.asSubclass(Enum.class), "OTHER");
            return ServerData.class
                    .getConstructor(String.class, String.class, typeClass)
                    .newInstance("mpmt-acceptance", address, other);
        } catch (ReflectiveOperationException ignored) {
            // 1.20.1: (name, ip, isLan)
        }
        try {
            return ServerData.class
                    .getConstructor(String.class, String.class, boolean.class)
                    .newInstance("mpmt-acceptance", address, false);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法构造 ServerData", error);
        }
    }

    private static Outcome evaluate() {
        try {
            return switch (active.getScenarioId()) {
                case "product-handshake" -> verifyHandshake();
                case "product-roundtrip" -> verifyRoundtrip();
                case "client-hud" -> verifyHud();
                default -> Outcome.error("无客户端验证器：" + active.getScenarioId());
            };
        } catch (RuntimeException e) {
            return Outcome.error("客户端验证异常：" + e.getMessage());
        }
    }

    private static Outcome verifyHandshake() {
        if (!"verify-handshake".equals(active.getStepId())) {
            return Outcome.error("未知握手步骤：" + active.getStepId());
        }
        ClientNetworkFeature feature = ForgeClientEvents.session().networkFeature();
        if (feature == null || !feature.handshakeClient().isAccepted()) {
            return null;
        }
        String sessionId = feature.handshakeClient().sessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return Outcome.fail("产品握手已接受但缺少 sessionId");
        }
        if (!"欢迎".equals(feature.handshakeClient().lastServerMessage())) {
            return null;
        }
        return Outcome.ok("{\"sessionId\":\"" + sessionId + "\"}", "产品握手与标识上报通过");
    }

    /**
     * 产品心跳方向是 S2C Ping / C2S Pong，由 HeartbeatService 自动应答。
     *
     * <p>客户端侧只确认握手仍有效，禁止主动发 Ping（会覆盖/冲突产品心跳处理器，
     * 且服务端不会对客户端 Ping 回 Pong）。与 Fabric / Forge 1.20 验证器对齐。
     */
    private static Outcome verifyRoundtrip() {
        if (!"verify-roundtrip".equals(active.getStepId())) {
            return Outcome.error("未知往返步骤：" + active.getStepId());
        }
        ClientNetworkFeature feature = ForgeClientEvents.session().networkFeature();
        if (feature == null || !feature.handshakeClient().isAccepted()) {
            return null;
        }
        String sessionId = feature.handshakeClient().sessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return Outcome.fail("产品握手已接受但缺少 sessionId");
        }
        return Outcome.ok("{\"sessionId\":\"" + sessionId + "\"}", "产品心跳往返后客户端会话仍有效");
    }

    private static Outcome verifyHud() {
        if (!"verify-hud".equals(active.getStepId())) {
            return Outcome.error("未知 HUD 步骤：" + active.getStepId());
        }
        // 握手成功后产品会先下发 TITLE/ACTIONBAR/TOAST/CHAT 演示包，CHAT 是最后一包；
        // 只看 hudSnapshot 会被演示 CHAT 覆盖而永远等不到验收 ACTIONBAR，
        // 故改用按类型隔离的 actionBarSnapshot。
        ForgeHudSnapshot snapshot = ForgeClientEvents.session().actionBarSnapshot();
        if (snapshot == null) {
            return null;
        }
        if (snapshot.kind() != HudKind.ACTIONBAR || !EXPECTED_HUD_TEXT.equals(snapshot.text())) {
            return null;
        }
        return Outcome.ok("{\"hud\":\"" + snapshot.text() + "\"}", "产品 ACTIONBAR HUD 通过");
    }

    private static void receive(byte[] data) {
        try {
            AcceptanceControlPacket packet = AcceptanceControlCodec.decode(data);
            if (packet instanceof RunStepPacket runStep) {
                INBOUND.add(runStep);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("丢弃非法验收控制包：{}", e.getMessage());
        }
    }

    private static void sendClientReady() {
        try {
            JavaRuntimeInfo javaInfo = AcceptanceReportV2Factory.currentJava(
                    System.getProperty("mpmt.acceptance.javaExecutable"));
            send(new ClientReadyPacket(
                    AcceptanceControlCodec.PROTOCOL_VERSION,
                    javaInfo.getMajor(),
                    javaInfo.getExecutable()));
        } catch (RuntimeException e) {
            LOGGER.error("无法上报客户端 Java 运行身份：{}", e.getMessage());
        }
    }

    private static void send(AcceptanceControlPacket packet) {
        Connection current = connection;
        if (current != null) {
            MpmtForge121AcceptanceMod.control().sendToServer(current, packet);
        }
    }

    private static void clear() {
        MpmtForge121AcceptanceMod.control().clearClientReceiver();
        connection = null;
        INBOUND.clear();
        active = null;
        ticksInStep = 0;
        pingSent = false;
        pongReceived = false;
    }

    private record Outcome(StepStatus status, String resultJson, String message) {

        private static Outcome ok(String resultJson, String message) {
            return new Outcome(StepStatus.OK, resultJson, message);
        }

        private static Outcome fail(String message) {
            return new Outcome(StepStatus.FAIL, "{}", message);
        }

        private static Outcome error(String message) {
            return new Outcome(StepStatus.ERROR, "{}", message);
        }
    }
}
