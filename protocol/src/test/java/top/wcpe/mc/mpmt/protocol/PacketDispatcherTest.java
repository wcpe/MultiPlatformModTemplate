package top.wcpe.mc.mpmt.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;

/** 收发管线：编码发送 / 解码路由 / 非法输入不崩溃 / 无处理器忽略（FR-19 验收）。 */
class PacketDispatcherTest {

    /** 假传输：捕获发出的字节，可注入接收。 */
    private static final class FakeTransport implements TransportPort {
        final List<byte[]> sent = new ArrayList<>();
        private BiConsumer<ConnectionHandle, byte[]> handler;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            sent.add(data);
        }

        @Override
        public void send(byte[] data) {
            sent.add(data);
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            this.handler = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 32767;
        }

        void receive(byte[] data) {
            handler.accept(null, data);
        }
    }

    @Test
    @DisplayName("发送：包被编码后写入传输")
    void 发送编码后写入传输() {
        FakeTransport transport = new FakeTransport();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher dispatcher = new PacketDispatcher(transport, codec);

        dispatcher.send(new PingPacket(7L));

        assertEquals(1, transport.sent.size());
        assertArrayEquals(codec.encode(new PingPacket(7L)), transport.sent.get(0));
    }

    @Test
    @DisplayName("接收：字节被解码后按 id 路由到处理器")
    void 接收解码后路由() {
        FakeTransport transport = new FakeTransport();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher dispatcher = new PacketDispatcher(transport, codec);
        List<Packet> received = new ArrayList<>();
        dispatcher.on(PacketIds.PONG, (connection, packet) -> received.add(packet));

        transport.receive(codec.encode(new PongPacket(99L)));

        assertEquals(1, received.size());
        assertEquals(new PongPacket(99L), received.get(0));
    }

    @Test
    @DisplayName("非法 / 截断字节：不崩溃、不路由")
    void 非法字节不崩溃() {
        FakeTransport transport = new FakeTransport();
        PacketDispatcher dispatcher = new PacketDispatcher(transport, new PacketCodec());
        List<Packet> received = new ArrayList<>();
        dispatcher.on(PacketIds.PONG, (connection, packet) -> received.add(packet));

        assertDoesNotThrow(() -> transport.receive(new byte[] {0x01}));
        assertDoesNotThrow(() -> transport.receive(new byte[0]));

        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("无处理器的包：静默忽略、不崩溃")
    void 无处理器忽略() {
        FakeTransport transport = new FakeTransport();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher dispatcher = new PacketDispatcher(transport, codec);

        assertDoesNotThrow(() -> transport.receive(codec.encode(new PingPacket(1L))));
    }
}
