package top.wcpe.mc.mpmt.core.server;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/**
 * 服务端握手服务（平台无关）：
 * 收 ClientHello → 版本协商 → 回 ServerHello；客户端上报标识 → 封禁校验 → 欢迎建立会话 / 告知封禁并通知断开。
 *
 * <p>每连接一台 {@link HandshakeStateMachine}。会话建立时序：版本兼容则 ServerHello(accepted)，
 * 收到 ClientIdReport 后若未封禁则 ESTABLISHED + 欢迎，封禁则 REJECTED + Disconnect（真实踢出由平台 L3 调度执行）。
 */
public final class HandshakeServerService {

    private static final Logger LOGGER = Logger.getLogger(HandshakeServerService.class.getName());

    private final PacketDispatcher dispatcher;
    private final Supplier<String> sessionIdSupplier;
    private final BanRegistry banRegistry;
    private final Map<ConnectionHandle, HandshakeStateMachine> handshakes = new ConcurrentHashMap<>();

    public HandshakeServerService(PacketDispatcher dispatcher, Supplier<String> sessionIdSupplier, BanRegistry banRegistry) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier 不能为空");
        this.banRegistry = Objects.requireNonNull(banRegistry, "banRegistry 不能为空");
        dispatcher.on(PacketIds.CLIENT_HELLO, this::onClientHello);
        dispatcher.on(PacketIds.CLIENT_ID_REPORT, this::onClientIdReport);
    }

    private void onClientHello(ConnectionHandle connection, Packet packet) {
        ClientHelloPacket hello = (ClientHelloPacket) packet;
        HandshakeStateMachine sm = handshakes.computeIfAbsent(connection, c -> new HandshakeStateMachine());
        if (sm.state() != HandshakeStateMachine.State.CONNECTED) {
            LOGGER.warning("重复 ClientHello，忽略；当前状态 " + sm.state());
            return;
        }
        boolean compatible = ProtocolVersion.isCompatible(hello.getProtocolVersion());
        sm.onClientHello(compatible);
        String sessionId = compatible ? sessionIdSupplier.get() : "";
        dispatcher.send(connection, new ServerHelloPacket(ProtocolVersion.CURRENT, sessionId, compatible));
    }

    private void onClientIdReport(ConnectionHandle connection, Packet packet) {
        ClientIdReportPacket report = (ClientIdReportPacket) packet;
        HandshakeStateMachine sm = handshakes.get(connection);
        if (sm == null || sm.state() != HandshakeStateMachine.State.HELLO_OK) {
            LOGGER.warning("意外的 ClientIdReport，忽略；当前状态 " + (sm == null ? "无" : sm.state()));
            return;
        }
        boolean banned = banRegistry.isBanned(new MachineCode(report.getClientId()));
        sm.onClientId(banned);
        if (banned) {
            // 先告知再通知断开；真实踢出由平台 L3 经 SchedulerPort.runForEntity 执行（ADR-0013）
            dispatcher.send(connection, new ServerMessagePacket("你的客户端标识已被封禁"));
            dispatcher.send(connection, new DisconnectPacket("banned"));
        } else {
            dispatcher.send(connection, new ServerMessagePacket("欢迎"));
        }
    }

    /** 查询某连接的握手状态（不存在返回 null）。 */
    public HandshakeStateMachine.State stateOf(ConnectionHandle connection) {
        HandshakeStateMachine sm = handshakes.get(connection);
        return sm == null ? null : sm.state();
    }
}
