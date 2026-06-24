package top.wcpe.mc.mpmt.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.client.HandshakeClientService;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.core.server.HandshakeServerService;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.ProtocolVersion;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;

/**
 * 冒烟集成：经进程内回环跑通"进服握手 + 版本协商 + 一次往返包"全链路（FR-11 ②，纯 JVM）。
 *
 * <p>证明客户端与服务端共用同一份 protocol（同一 codec）即可互通——桥接价值的逻辑证明；
 * 真实异构客户端 ↔ 服务端（Fabric/Forge ↔ Paper）互通为实机维度（PRD §6）。
 */
class SmokeHandshakeRoundTripTest {

    @Test
    @DisplayName("握手成功并完成 Ping/Pong 往返")
    void 握手并往返() {
        InProcessLoopbackTransport loop = new InProcessLoopbackTransport();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher serverDispatcher = new PacketDispatcher(loop.server(), codec);
        PacketDispatcher clientDispatcher = new PacketDispatcher(loop.client(), codec);

        AtomicLong sessionSeq = new AtomicLong();
        HandshakeServerService server =
                new HandshakeServerService(serverDispatcher, () -> "session-" + sessionSeq.incrementAndGet());
        HandshakeClientService client = new HandshakeClientService(clientDispatcher, "1.0.0-test");

        // demo 往返：服务端收 Ping 原样回 Pong
        serverDispatcher.on(PacketIds.PING,
                (conn, packet) -> serverDispatcher.send(conn, new PongPacket(((PingPacket) packet).getNonce())));
        List<Long> pongs = new ArrayList<>();
        clientDispatcher.on(PacketIds.PONG, (conn, packet) -> pongs.add(((PongPacket) packet).getNonce()));

        // 握手（回环同步完成）
        client.startHandshake();
        assertTrue(client.isAccepted(), "兼容版本应被接受");
        assertEquals("session-1", client.sessionId());
        assertEquals(HandshakeStateMachine.State.ESTABLISHED, server.stateOf(loop.clientConnection()));

        // 一次往返
        clientDispatcher.send(new PingPacket(12345L));
        assertEquals(1, pongs.size());
        assertEquals(12345L, pongs.get(0));
    }

    @Test
    @DisplayName("版本不兼容：服务端拒绝、不建立会话")
    void 版本不兼容被拒() {
        InProcessLoopbackTransport loop = new InProcessLoopbackTransport();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher serverDispatcher = new PacketDispatcher(loop.server(), codec);
        PacketDispatcher clientDispatcher = new PacketDispatcher(loop.client(), codec);
        HandshakeServerService server = new HandshakeServerService(serverDispatcher, () -> "session-x");

        boolean[] accepted = {true};
        clientDispatcher.on(PacketIds.SERVER_HELLO,
                (conn, packet) -> accepted[0] = ((ServerHelloPacket) packet).isAccepted());

        // 直接发不兼容版本的 ClientHello，模拟过新 / 过旧端
        clientDispatcher.send(new ClientHelloPacket(ProtocolVersion.CURRENT + 1, "incompatible"));

        assertFalse(accepted[0], "不兼容版本应被拒");
        assertEquals(HandshakeStateMachine.State.REJECTED, server.stateOf(loop.clientConnection()));
    }
}
