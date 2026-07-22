package top.wcpe.mc.mpmt.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;

/** 客户端心跳响应器：服务端 Ping 到达后立即回 Pong，并验证关闭后的清理行为（FR-28）。 */
class HeartbeatServiceTest {

    private final PacketCodec codec = new PacketCodec();

    @Test
    @DisplayName("收到服务端 Ping 立即回送相同 nonce 的 Pong")
    void 响应服务端Ping() {
        FakeTransport transport = new FakeTransport();
        new HeartbeatService(new PacketDispatcher(transport, codec));

        transport.receive(codec.encode(new PingPacket(42L)));

        PongPacket pong = (PongPacket) lastSent(transport);
        assertEquals(42L, pong.getNonce());
    }

    @Test
    @DisplayName("close 幂等清理，关闭后不再响应 Ping")
    void 关闭后停止响应() {
        FakeTransport transport = new FakeTransport();
        HeartbeatService service = new HeartbeatService(new PacketDispatcher(transport, codec));

        service.close();
        service.close();
        transport.receive(codec.encode(new PingPacket(7L)));

        assertEquals(0, transport.sends.size());
    }

    @Test
    @DisplayName("构造依赖为空即拒")
    void 入参校验() {
        assertThrows(NullPointerException.class, () -> new HeartbeatService(null));
    }

    private Packet lastSent(FakeTransport transport) {
        return codec.decode(transport.sends.get(transport.sends.size() - 1));
    }

    /** 假客户端传输：捕获发包并可注入服务端请求。 */
    private static final class FakeTransport implements TransportPort {
        private static final ConnectionHandle SERVER = new ConnectionHandle() {
        };

        final List<byte[]> sends = new ArrayList<>();
        private BiConsumer<ConnectionHandle, byte[]> receiver;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            throw new UnsupportedOperationException("客户端传输只无连接发送");
        }

        @Override
        public void send(byte[] data) {
            sends.add(data);
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            this.receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 32767;
        }

        void receive(byte[] data) {
            receiver.accept(SERVER, data);
        }
    }
}
