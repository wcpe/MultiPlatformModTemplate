package top.wcpe.mc.mpmt.core.server;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.ProtocolVersion;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;

/**
 * 服务端握手服务（平台无关）：收 ClientHello → 版本协商 → 回 ServerHello（接受 / 拒绝）。
 *
 * <p>每连接一台 {@link HandshakeStateMachine}；版本兼容则建立会话并分配会话 id，不兼容则回 accepted=false。
 * 重复 ClientHello 被忽略。会话 id 由注入的 {@link Supplier} 生成（便于测试确定化）。
 */
public final class HandshakeServerService {

    private static final Logger LOGGER = Logger.getLogger(HandshakeServerService.class.getName());

    private final PacketDispatcher dispatcher;
    private final Supplier<String> sessionIdSupplier;
    private final Map<ConnectionHandle, HandshakeStateMachine> handshakes = new ConcurrentHashMap<>();

    public HandshakeServerService(PacketDispatcher dispatcher, Supplier<String> sessionIdSupplier) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier 不能为空");
        dispatcher.on(PacketIds.CLIENT_HELLO, this::onClientHello);
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
        // 先发应答（成功后才推进会话），保证对外副作用与状态一致
        dispatcher.send(connection, new ServerHelloPacket(ProtocolVersion.CURRENT, sessionId, compatible));
        if (compatible) {
            sm.onEstablished();
        }
    }

    /** 查询某连接的握手状态（不存在返回 null）。 */
    public HandshakeStateMachine.State stateOf(ConnectionHandle connection) {
        HandshakeStateMachine sm = handshakes.get(connection);
        return sm == null ? null : sm.state();
    }
}
