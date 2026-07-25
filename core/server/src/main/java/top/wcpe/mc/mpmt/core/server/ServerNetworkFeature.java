package top.wcpe.mc.mpmt.core.server;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.runtime.Feature;
import top.wcpe.mc.mpmt.core.runtime.RuntimeContext;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;
import top.wcpe.mc.mpmt.protocol.reliability.ResyncCoordinator;

/** 服务端网络装配：共享会话、握手、心跳、重同步、HUD 与协议维护。 */
public final class ServerNetworkFeature implements Feature {

    private static final String NAME = "server-network";

    private final BanRegistry banRegistry;
    private final Supplier<String> sessionIdSupplier;
    private final Supplier<SessionRegistry> sessionRegistrySupplier;
    private final Supplier<BanService.State> banStateSupplier;

    private PacketDispatcher dispatcher;
    private HandshakeServerService handshakeService;
    private HeartbeatService heartbeatService;
    private HudMessageService hudMessageService;
    private ResyncCoordinator resyncCoordinator;

    /**
     * 兼容签名不再创建私有会话表；调用方必须显式注入共享的 {@link SessionRegistry}。
     *
     * @deprecated 请使用包含 SessionRegistry 的构造器
     */
    @Deprecated
    public ServerNetworkFeature(BanRegistry banRegistry, Supplier<String> sessionIdSupplier) {
        throw new IllegalArgumentException("必须显式注入共享 SessionRegistry");
    }

    public ServerNetworkFeature(
            BanRegistry banRegistry,
            Supplier<String> sessionIdSupplier,
            SessionRegistry sessionRegistry) {
        this(
                banRegistry,
                sessionIdSupplier,
                sessionRegistry,
                () -> BanService.State.READY);
    }

    public ServerNetworkFeature(
            BanRegistry banRegistry,
            Supplier<String> sessionIdSupplier,
            SessionRegistry sessionRegistry,
            Supplier<BanService.State> banStateSupplier) {
        this.banRegistry = Objects.requireNonNull(banRegistry, "banRegistry 不能为空");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier 不能为空");
        SessionRegistry shared = Objects.requireNonNull(sessionRegistry, "sessionRegistry 不能为空");
        this.sessionRegistrySupplier = () -> shared;
        this.banStateSupplier = Objects.requireNonNull(banStateSupplier, "banStateSupplier 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onEnable(RuntimeContext context) {
        TransportPort transport = context.port(TransportPort.class);
        SchedulerPort scheduler = context.port(SchedulerPort.class);
        ConnectionControlPort connections = context.port(ConnectionControlPort.class);
        this.dispatcher = new PacketDispatcher(transport, new PacketCodec());
        this.handshakeService = new HandshakeServerService(
                dispatcher,
                sessionIdSupplier,
                banRegistry,
                sessionRegistry(),
                banStateSupplier,
                (connection, reason, currentCheck) ->
                        scheduleDisconnect(
                                scheduler,
                                connections,
                                connection,
                                reason,
                                currentCheck));
        this.heartbeatService =
                new HeartbeatService(sessionRegistry(), dispatcher, scheduler, connections);
        this.hudMessageService = new HudMessageService(dispatcher);
        this.resyncCoordinator = ResyncCoordinator.forServer(dispatcher, this::onResyncRequest);
    }

    @Override
    public void onDisable(RuntimeContext context) {
        if (heartbeatService != null) {
            heartbeatService.close();
        }
    }

    /** 新物理连接建立时重置握手与旧会话。 */
    public void onConnected(ConnectionHandle connection) {
        required(handshakeService).onConnected(connection);
    }

    /** 物理连接断开时清理握手、会话、心跳与协议可靠性状态。 */
    public void onDisconnected(ConnectionHandle connection) {
        HandshakeServerService handshake = required(handshakeService);
        boolean current = handshake.stateOf(connection) != null
                || sessionRegistry().get(connection).isPresent();
        required(heartbeatService).onDisconnected(connection);
        handshake.onDisconnected(connection);
        if (current) {
            required(dispatcher).onDisconnected(connection);
        }
    }

    /** 构造方注入并由握手、心跳、重同步共同使用的会话注册表。 */
    public SessionRegistry sessionRegistry() {
        return sessionRegistrySupplier.get();
    }

    /** 握手服务（启用后可取）。 */
    public HandshakeServerService handshakeService() {
        return required(handshakeService);
    }

    /** 服务端心跳服务（启用后可取）。 */
    public HeartbeatService heartbeatService() {
        return required(heartbeatService);
    }

    /** 重连重同步协调器（启用后可取）。 */
    public ResyncCoordinator resyncCoordinator() {
        return required(resyncCoordinator);
    }

    /** HUD 下发服务（启用后可取）。 */
    public HudMessageService hudMessageService() {
        return required(hudMessageService);
    }

    private static void scheduleDisconnect(
            SchedulerPort scheduler,
            ConnectionControlPort connections,
            ConnectionHandle connection,
            String reason,
            BooleanSupplier currentCheck) {
        EntityRef entity = connections.entityOf(connection);
        scheduler.runForEntity(
                entity,
                () -> {
                    if (currentCheck.getAsBoolean()) {
                        connections.disconnect(connection, reason);
                    }
                });
    }

    private void onResyncRequest(ConnectionHandle connection, long sinceRevision) {
        Optional<SessionRegistry.Session> current = sessionRegistry().get(connection);
        if (!current.isPresent()) {
            return;
        }
        dispatcher.send(
                connection,
                new ServerMessagePacket(
                        "已按修订 " + sinceRevision + " 重同步（服务端重发权威状态）"));
        sessionRegistry()
                .markResyncComplete(current.get(), sinceRevision)
                .ifPresent(heartbeatService::onResyncComplete);
    }

    private static <T> T required(T value) {
        if (value == null) {
            throw new IllegalStateException("服务端网络特性尚未启用");
        }
        return value;
    }
}
