package top.wcpe.mc.mpmt.platform.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricClientNetwork;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;

/** 客户端 play 连接生命周期：JOIN 装配收包，就绪后握手，断线丢弃会话。 */
class FabricClientSessionTest {

    @Test
    @DisplayName("join 装配收包但不握手，startHandshakeWhenReady 再发 ClientHello")
    void 连接生命周期() {
        FakeClientNetwork network = new FakeClientNetwork();
        FabricClientSession session =
                new FabricClientSession(network, "test-mod", () -> "test-code");

        session.join();

        assertNotNull(session.networkFeature());
        assertTrue(network.hasReceiver());
        assertTrue(network.sent.isEmpty(), "join 不应立即发 ClientHello");

        session.startHandshakeWhenReady();

        ClientHelloPacket hello =
                (ClientHelloPacket) new PacketCodec().decode(network.sent.get(0));
        assertEquals("test-mod", hello.getModVersion());
        int sentAfterFirst = network.sent.size();
        session.startHandshakeWhenReady();
        assertEquals(sentAfterFirst, network.sent.size(), "握手只应发起一次");

        session.disconnect();

        assertNull(session.networkFeature());
        assertFalse(network.hasReceiver());
    }

    private static final class FakeClientNetwork implements FabricClientNetwork {
        private final List<byte[]> sent = new ArrayList<>();
        private Consumer<byte[]> receiver;

        @Override
        public void registerReceiver(Consumer<byte[]> handler) {
            receiver = handler;
        }

        @Override
        public void clearReceiver() {
            receiver = null;
        }

        @Override
        public void send(byte[] data) {
            sent.add(data.clone());
        }

        @Override
        public int maxPayloadSize() {
            return 1048576;
        }

        boolean hasReceiver() {
            return receiver != null;
        }
    }
}
