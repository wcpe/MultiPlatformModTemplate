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
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/** 收发管线：编码发送 / 解码路由 / 非法输入不崩溃 / 无处理器忽略（FR-19）+ 可靠性层透明分片重组（FR-24）。 */
class PacketDispatcherTest {

    /** 假传输：捕获发出的字节，可注入接收；单包上限可配（默认 32767，调小以触发分片）。 */
    private static final class FakeTransport implements TransportPort {
        final List<byte[]> sent = new ArrayList<>();
        int maxPayload = 32767;
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
            return maxPayload;
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
        // 构造即把 dispatcher 注册到 transport.onReceive（副作用），故无需持有引用
        new PacketDispatcher(transport, codec);

        assertDoesNotThrow(() -> transport.receive(codec.encode(new PingPacket(1L))));
    }

    @Test
    @DisplayName("可靠性层：超单包上限的包透明分片，接收端重组后路由原包（FR-24）")
    void 超上限包透明分片重组往返() {
        FakeTransport transport = new FakeTransport();
        transport.maxPayload = 64; // 调小单包上限以触发分片
        PacketCodec codec = new PacketCodec();
        PacketDispatcher dispatcher = new PacketDispatcher(transport, codec);
        List<Packet> received = new ArrayList<>();
        dispatcher.on(PacketIds.SERVER_MESSAGE, (connection, packet) -> received.add(packet));

        // 远超 64 字节上限的 ServerMessage（500 字符）
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            big.append('A');
        }
        ServerMessagePacket original = new ServerMessagePacket(big.toString());
        dispatcher.send(original);

        // 已被切成多片，每片是分片包且不超过单包上限
        assertTrue(transport.sent.size() > 1, "超上限包应被切成多片，实际片数=" + transport.sent.size());
        for (byte[] frame : transport.sent) {
            assertEquals(PacketIds.FRAGMENT, codec.decode(frame).id(), "每片应为分片包");
            assertTrue(frame.length <= 64, "每片不应超过单包上限，实际=" + frame.length);
        }

        // 回灌各片：集齐 + CRC 通过后应路由出原包
        for (byte[] frame : transport.sent) {
            transport.receive(frame);
        }
        assertEquals(1, received.size(), "重组后应路由出 1 个原包");
        assertEquals(original, received.get(0), "重组出的应为原 ServerMessage");
    }

    @Test
    @DisplayName("可靠性层：不超上限的小包不分片、原样收发（FR-24 不回归 FR-19）")
    void 小包不分片() {
        FakeTransport transport = new FakeTransport();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher dispatcher = new PacketDispatcher(transport, codec);
        List<Packet> received = new ArrayList<>();
        dispatcher.on(PacketIds.PONG, (connection, packet) -> received.add(packet));

        dispatcher.send(new PongPacket(5L));
        assertEquals(1, transport.sent.size(), "小包应原样单帧发送、不分片");
        assertEquals(PacketIds.PONG, codec.decode(transport.sent.get(0)).id(), "应直发原包、非分片包");

        transport.receive(transport.sent.get(0));
        assertEquals(1, received.size());
        assertEquals(new PongPacket(5L), received.get(0));
    }
}
