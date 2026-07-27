package top.wcpe.mc.mpmt.platform.forge.modern.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ClientIdReportPacket;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/** 客户端会话覆盖连接握手、Ping/Pong、HUD 与断线清理。 */
class ForgeClientSessionTest {

    @Test
    @DisplayName("连接后握手并使用共享产品协议，断线清理会话和 HUD")
    void 产品客户端完整会话() {
        PacketCodec codec = new PacketCodec();
        FakeTransport transport = new FakeTransport();
        ForgeClientSession session =
                new ForgeClientSession(new ForgeClientHud(), "test-mod", () -> "test-code");

        session.joinForTest(transport);

        ClientHelloPacket hello = assertInstanceOf(
                ClientHelloPacket.class, codec.decode(transport.sent.get(0)));
        assertEquals("test-mod", hello.getModVersion());
        transport.receive(codec.encode(new ServerHelloPacket(1, "session-1", true)));
        ClientIdReportPacket report = assertInstanceOf(
                ClientIdReportPacket.class, codec.decode(transport.sent.get(1)));
        assertEquals("test-code", report.getClientId());
        transport.receive(codec.encode(new ServerMessagePacket("欢迎")));
        assertTrue(session.networkFeature().handshakeClient().isAccepted());

        boolean[] received = {false};
        session.networkFeature().dispatcher().on(
                PacketIds.PONG,
                (ignored, packet) -> received[0] = ((PongPacket) packet).getNonce() == 7L);
        session.networkFeature().dispatcher().send(new PingPacket(7L));
        Packet ping = codec.decode(transport.sent.get(transport.sent.size() - 1));
        assertInstanceOf(PingPacket.class, ping);
        transport.receive(codec.encode(new PongPacket(7L)));
        assertTrue(received[0]);

        transport.receive(codec.encode(new ServerHudMessagePacket(
                HudKind.ACTIONBAR, "验收HUD", "", 1000L)));
        ForgeHudSnapshot snapshot = session.hudSnapshot();
        assertNotNull(snapshot);
        assertEquals(HudKind.ACTIONBAR, snapshot.kind());
        assertEquals("验收HUD", snapshot.text());

        session.disconnect();

        assertNull(session.networkFeature());
        assertNull(session.hudSnapshot());
        assertFalse(transport.sent.isEmpty());
    }

    private static final class FakeTransport implements TransportPort {

        private static final ConnectionHandle SERVER = new ConnectionHandle() {
        };

        private final List<byte[]> sent = new ArrayList<>();
        private BiConsumer<ConnectionHandle, byte[]> receiver;

        @Override
        public void send(ConnectionHandle ignored, byte[] data) {
            throw new UnsupportedOperationException("测试客户端不向任意连接发送");
        }

        @Override
        public void send(byte[] data) {
            sent.add(data.clone());
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 1_048_576;
        }

        private void receive(byte[] data) {
            receiver.accept(SERVER, data);
        }
    }
}
