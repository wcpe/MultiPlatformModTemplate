package top.wcpe.mc.mpmt.core.server;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.ProtocolVersion;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ClientIdReportPacket;
import top.wcpe.mc.mpmt.protocol.packet.DisconnectPacket;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/** 服务端握手服务：版本协商、封禁门禁、会话登记及拒绝断开请求。 */
public final class HandshakeServerService {

    /** 拒绝握手后的真实断开请求；执行前必须调用 currentCheck 重检物理连接上下文。 */
    @FunctionalInterface
    public interface DisconnectHandler {
        void disconnect(ConnectionHandle connection, String reason, BooleanSupplier currentCheck);
    }

    private static final Logger LOGGER = Logger.getLogger(HandshakeServerService.class.getName());
    private static final String BANNED_MESSAGE = "你的客户端标识已被封禁";
    private static final String NOT_READY_MESSAGE = "封禁服务尚未就绪，请稍后重试";
    /** 握手成功欢迎语（聊天包，兼容旧客户端）。 */
    private static final String WELCOME_MESSAGE = "欢迎";
    /** 握手成功后演示用 HUD 标题。 */
    private static final String DEMO_TITLE = "MPMT 握手成功";
    /** 握手成功后演示用 HUD 副标题。 */
    private static final String DEMO_SUBTITLE = "跨端网络已就绪";
    /** 握手成功后演示用 ACTIONBAR。 */
    private static final String DEMO_ACTIONBAR = "ACTIONBAR：跨端 HUD 演示";
    /** 握手成功后演示用 TOAST 标题。 */
    private static final String DEMO_TOAST = "TOAST：跨端 HUD 演示";
    /** 握手成功后演示用 TOAST 副标题。 */
    private static final String DEMO_TOAST_SUBTITLE = "title / actionbar / toast / chat";
    /** 握手成功后演示用 HUD 聊天。 */
    private static final String DEMO_HUD_CHAT = "CHAT：跨端 HUD 演示";
    /** 演示标题默认展示时长（毫秒）。 */
    private static final long DEMO_TITLE_DURATION_MS = 3500L;
    private static final DisconnectHandler NOOP_DISCONNECT = (connection, reason, currentCheck) -> {
    };

    private final PacketDispatcher dispatcher;
    private final Supplier<String> sessionIdSupplier;
    private final BanRegistry banRegistry;
    private final SessionRegistry sessionRegistry;
    private final Supplier<BanService.State> banStateSupplier;
    private final DisconnectHandler disconnectHandler;
    private final Map<ConnectionHandle, HandshakeContext> handshakes = new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong();

    public HandshakeServerService(PacketDispatcher dispatcher, Supplier<String> sessionIdSupplier, BanRegistry banRegistry) {
        this(dispatcher, sessionIdSupplier, banRegistry, new SessionRegistry());
    }

    public HandshakeServerService(
            PacketDispatcher dispatcher,
            Supplier<String> sessionIdSupplier,
            BanRegistry banRegistry,
            SessionRegistry sessionRegistry) {
        this(
                dispatcher,
                sessionIdSupplier,
                banRegistry,
                sessionRegistry,
                () -> BanService.State.READY,
                NOOP_DISCONNECT);
    }

    public HandshakeServerService(
            PacketDispatcher dispatcher,
            Supplier<String> sessionIdSupplier,
            BanRegistry banRegistry,
            SessionRegistry sessionRegistry,
            Supplier<BanService.State> banStateSupplier,
            DisconnectHandler disconnectHandler) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier 不能为空");
        this.banRegistry = Objects.requireNonNull(banRegistry, "banRegistry 不能为空");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry 不能为空");
        this.banStateSupplier = Objects.requireNonNull(banStateSupplier, "banStateSupplier 不能为空");
        this.disconnectHandler = Objects.requireNonNull(disconnectHandler, "disconnectHandler 不能为空");
        dispatcher.on(PacketIds.CLIENT_HELLO, this::onClientHello);
        dispatcher.on(PacketIds.CLIENT_ID_REPORT, this::onClientIdReport);
    }

    /** 新物理连接建立时清除同 UUID 旧握手和旧会话。 */
    public void onConnected(ConnectionHandle connection) {
        Objects.requireNonNull(connection, "connection 不能为空");
        sessionRegistry.removeForReconnect(connection);
        handshakes.put(
                connection,
                new HandshakeContext(connection, generationSequence.incrementAndGet()));
    }

    /** 物理连接断开时仅清理该连接自己的握手和会话。 */
    public void onDisconnected(ConnectionHandle connection) {
        Objects.requireNonNull(connection, "connection 不能为空");
        handshakes.computeIfPresent(
                connection,
                (key, current) -> sameConnection(current.connection, connection) ? null : current);
        sessionRegistry.remove(connection);
    }

    private void onClientHello(ConnectionHandle connection, Packet packet) {
        HandshakeContext context = handshakes.get(connection);
        if (context == null) {
            onConnected(connection);
            context = currentContext(connection);
        } else if (!sameConnection(context.connection, connection)) {
            LOGGER.warning("旧物理连接的 ClientHello，忽略");
            return;
        }
        synchronized (context) {
            handleClientHello(connection, (ClientHelloPacket) packet, context);
        }
    }

    private void handleClientHello(
            ConnectionHandle connection, ClientHelloPacket hello, HandshakeContext context) {
        if (context.stateMachine.state() != HandshakeStateMachine.State.CONNECTED) {
            LOGGER.warning("重复 ClientHello，忽略；当前状态 " + context.stateMachine.state());
            return;
        }
        boolean compatible = ProtocolVersion.isCompatible(hello.getProtocolVersion());
        context.stateMachine.onClientHello(compatible);
        context.sessionId = compatible ? sessionIdSupplier.get() : "";
        dispatcher.send(
                connection,
                new ServerHelloPacket(ProtocolVersion.CURRENT, context.sessionId, compatible));
    }

    private void onClientIdReport(ConnectionHandle connection, Packet packet) {
        HandshakeContext context = currentContext(connection);
        if (context == null) {
            LOGGER.warning("意外的 ClientIdReport，忽略；当前状态 无");
            return;
        }
        synchronized (context) {
            handleClientIdReport(connection, (ClientIdReportPacket) packet, context);
        }
    }

    private void handleClientIdReport(
            ConnectionHandle connection, ClientIdReportPacket report, HandshakeContext context) {
        if (context.stateMachine.state() != HandshakeStateMachine.State.HELLO_OK) {
            LOGGER.warning("意外的 ClientIdReport，忽略；当前状态 " + context.stateMachine.state());
            return;
        }
        if (banStateSupplier.get() != BanService.State.READY) {
            reject(connection, context, NOT_READY_MESSAGE);
            return;
        }
        MachineCode machineCode = new MachineCode(report.getClientId());
        if (banRegistry.isBanned(machineCode)) {
            reject(connection, context, BANNED_MESSAGE);
            return;
        }
        context.stateMachine.onClientId(false);
        sessionRegistry.register(connection, context.sessionId, machineCode);
        // 聊天欢迎：兼容只监听 ServerMessage 的旧路径
        dispatcher.send(connection, new ServerMessagePacket(WELCOME_MESSAGE));
        // FR-27 演示：握手成功后主动下发 title / actionbar / toast / chat，避免产品路径只剩 MessagePort 聊天心跳
        sendHandshakeHudDemo(connection);
    }

    /** 向刚完成握手的连接下发四类 HUD 演示包。 */
    private void sendHandshakeHudDemo(ConnectionHandle connection) {
        dispatcher.send(
                connection,
                new ServerHudMessagePacket(
                        HudKind.TITLE, DEMO_TITLE, DEMO_SUBTITLE, DEMO_TITLE_DURATION_MS));
        dispatcher.send(
                connection, new ServerHudMessagePacket(HudKind.ACTIONBAR, DEMO_ACTIONBAR, "", 0L));
        dispatcher.send(
                connection,
                new ServerHudMessagePacket(HudKind.TOAST, DEMO_TOAST, DEMO_TOAST_SUBTITLE, 0L));
        dispatcher.send(
                connection, new ServerHudMessagePacket(HudKind.CHAT, DEMO_HUD_CHAT, "", 0L));
    }

    private void reject(
            ConnectionHandle connection, HandshakeContext context, String message) {
        context.stateMachine.onClientId(true);
        sessionRegistry.remove(connection);
        dispatcher.send(connection, new ServerMessagePacket(message));
        dispatcher.send(connection, new DisconnectPacket(message));
        disconnectHandler.disconnect(
                connection,
                message,
                () -> isCurrentRejected(connection, context.generation));
    }

    /** 查询某物理连接的握手状态（不存在或已被新连接替换时返回 null）。 */
    public HandshakeStateMachine.State stateOf(ConnectionHandle connection) {
        HandshakeContext context = currentContext(connection);
        if (context == null) {
            return null;
        }
        synchronized (context) {
            return context.stateMachine.state();
        }
    }

    private boolean isCurrentRejected(ConnectionHandle connection, long generation) {
        HandshakeContext context = currentContext(connection);
        if (context == null || context.generation != generation) {
            return false;
        }
        synchronized (context) {
            return context.stateMachine.state() == HandshakeStateMachine.State.REJECTED;
        }
    }

    private HandshakeContext currentContext(ConnectionHandle connection) {
        HandshakeContext context = handshakes.get(connection);
        return context != null && sameConnection(context.connection, connection) ? context : null;
    }

    /** 物理连接必须按对象身份比较，避免同 UUID 新连接继承旧握手。 */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static boolean sameConnection(ConnectionHandle left, ConnectionHandle right) {
        return left == right;
    }

    private static final class HandshakeContext {
        private final ConnectionHandle connection;
        private final long generation;
        private final HandshakeStateMachine stateMachine = new HandshakeStateMachine();
        private String sessionId = "";

        private HandshakeContext(ConnectionHandle connection, long generation) {
            this.connection = connection;
            this.generation = generation;
        }
    }
}
