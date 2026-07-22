package top.wcpe.mc.mpmt.core.client;

import java.util.Objects;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;

/** 客户端心跳响应器（FR-28）：收到服务端 Ping 后立即回送相同 nonce 的 Pong。 */
public final class HeartbeatService implements AutoCloseable {

    private final PacketDispatcher dispatcher;
    private boolean closed;

    public HeartbeatService(PacketDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        dispatcher.on(PacketIds.PING, this::onPing);
    }

    private synchronized void onPing(ConnectionHandle connection, Packet packet) {
        if (closed) {
            return;
        }
        dispatcher.send(new PongPacket(((PingPacket) packet).getNonce()));
    }

    /** 停止响应后续 Ping；可重复调用。 */
    @Override
    public synchronized void close() {
        closed = true;
    }
}
