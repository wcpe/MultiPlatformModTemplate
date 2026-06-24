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
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
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
 * 冒烟集成：经进程内回环跑通"进服握手 + 版本协商 + 标识上报 + 封禁判定 + 一次往返包"全链路（FR-11 ②，纯 JVM）。
 *
 * <p>证明客户端与服务端共用同一份 protocol 即可互通；真实异构客户端 ↔ 服务端互通为实机维度（PRD §6）。
 */
class SmokeHandshakeRoundTripTest {

    /** 组装一对回环连接的客户端 / 服务端收发管线。 */
    private static final class Wiring {
        final InProcessLoopbackTransport loop = new InProcessLoopbackTransport();
        final PacketCodec codec = new PacketCodec();
        final PacketDispatcher server = new PacketDispatcher(loop.server(), codec);
        final PacketDispatcher client = new PacketDispatcher(loop.client(), codec);
    }

    @Test
    @DisplayName("握手成功（含标识上报+欢迎）并完成 Ping/Pong 往返")
    void 握手并往返() {
        Wiring w = new Wiring();
        AtomicLong sessionSeq = new AtomicLong();
        HandshakeServerService server =
                new HandshakeServerService(w.server, () -> "session-" + sessionSeq.incrementAndGet(), new BanRegistry());
        HandshakeClientService client =
                new HandshakeClientService(w.client, "1.0.0-test", () -> "client-honest");

        // demo 往返：服务端收 Ping 原样回 Pong
        w.server.on(PacketIds.PING, (conn, p) -> w.server.send(conn, new PongPacket(((PingPacket) p).getNonce())));
        List<Long> pongs = new ArrayList<>();
        w.client.on(PacketIds.PONG, (conn, p) -> pongs.add(((PongPacket) p).getNonce()));

        client.startHandshake();

        assertTrue(client.isAccepted(), "兼容版本应被接受");
        assertEquals("session-1", client.sessionId());
        assertEquals("欢迎", client.lastServerMessage(), "应收到欢迎消息");
        assertFalse(client.isDisconnected());
        assertEquals(HandshakeStateMachine.State.ESTABLISHED, server.stateOf(w.loop.clientConnection()));

        w.client.send(new PingPacket(12345L));
        assertEquals(1, pongs.size());
        assertEquals(12345L, pongs.get(0));
    }

    @Test
    @DisplayName("被封禁标识：握手后被告知并通知断开、不建立会话")
    void 封禁被踢() {
        Wiring w = new Wiring();
        BanRegistry bans = new BanRegistry();
        bans.ban(new MachineCode("client-bad"), "作弊");
        HandshakeServerService server = new HandshakeServerService(w.server, () -> "session-x", bans);
        HandshakeClientService client = new HandshakeClientService(w.client, "v", () -> "client-bad");

        client.startHandshake();

        assertTrue(client.isAccepted(), "版本兼容（封禁在标识上报后判定）");
        assertTrue(client.isDisconnected(), "被封禁应收到断开通知");
        assertEquals("banned", client.disconnectReason());
        assertEquals(HandshakeStateMachine.State.REJECTED, server.stateOf(w.loop.clientConnection()));
    }

    @Test
    @DisplayName("版本不兼容：服务端拒绝、不建立会话、不进入标识上报")
    void 版本不兼容被拒() {
        Wiring w = new Wiring();
        HandshakeServerService server = new HandshakeServerService(w.server, () -> "session-x", new BanRegistry());

        boolean[] accepted = {true};
        w.client.on(PacketIds.SERVER_HELLO, (conn, p) -> accepted[0] = ((ServerHelloPacket) p).isAccepted());

        // 直接发不兼容版本的 ClientHello，模拟过新 / 过旧端
        w.client.send(new ClientHelloPacket(ProtocolVersion.CURRENT + 1, "incompatible"));

        assertFalse(accepted[0], "不兼容版本应被拒");
        assertEquals(HandshakeStateMachine.State.REJECTED, server.stateOf(w.loop.clientConnection()));
    }
}
