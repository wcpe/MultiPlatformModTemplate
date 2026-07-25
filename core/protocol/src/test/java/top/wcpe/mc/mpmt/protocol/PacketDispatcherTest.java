package top.wcpe.mc.mpmt.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.protocol.packet.FragmentPacket;
import top.wcpe.mc.mpmt.protocol.packet.FragmentRetryRequestPacket;
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
            receive(null, data);
        }

        void receive(ConnectionHandle connection, byte[] data) {
            handler.accept(connection, data);
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
    @DisplayName("可靠性层：接收端首次超时跨端请求发送端重发整组，完成后去重")
    void 首次超时跨端重发整组() {
        FakeTransport senderTransport = fragmentTransport();
        FakeTransport receiverTransport = fragmentTransport();
        PacketCodec codec = new PacketCodec();
        AtomicLong clock = new AtomicLong(0L);
        List<Integer> senderResyncs = new ArrayList<>();
        PacketDispatcher sender = dispatcher(senderTransport, codec, clock, senderResyncs);
        PacketDispatcher receiver = dispatcher(receiverTransport, codec, clock, new ArrayList<>());
        List<Packet> received = new ArrayList<>();
        receiver.on(PacketIds.SERVER_MESSAGE, (connection, packet) -> received.add(packet));
        ConnectionHandle connection = new ConnectionHandle() { };

        sender.send(connection, largeMessage());
        int fragmentCount = senderTransport.sent.size();
        receiverTransport.receive(senderTransport.sent.get(0));
        clock.set(1001L);
        receiver.tickReliability();

        FragmentRetryRequestPacket retry = retryRequest(codec, receiverTransport.sent.get(0));
        senderTransport.receive(connection, receiverTransport.sent.get(0));
        assertEquals(fragmentCount * 2, senderTransport.sent.size());
        assertEquals(((FragmentPacket) codec.decode(senderTransport.sent.get(0))).getSeqId(), retry.getSeqId());
        assertFragmentGroupEquals(senderTransport.sent, fragmentCount);

        deliverRange(receiverTransport, senderTransport.sent, fragmentCount, fragmentCount * 2);
        deliverRange(receiverTransport, senderTransport.sent, 0, fragmentCount);
        assertEquals(1, received.size(), "完成组的迟到重复分片不应再次路由");
        assertTrue(senderResyncs.isEmpty());
    }

    @Test
    @DisplayName("可靠性层：客户端发送的大包同样支持服务端跨端请求重发")
    void 客户端方向跨端重发() {
        FakeTransport clientTransport = fragmentTransport();
        FakeTransport serverTransport = fragmentTransport();
        PacketCodec codec = new PacketCodec();
        AtomicLong clock = new AtomicLong(0L);
        PacketDispatcher client = dispatcher(clientTransport, codec, clock, new ArrayList<>());
        PacketDispatcher server = dispatcher(serverTransport, codec, clock, new ArrayList<>());
        ConnectionHandle connection = new ConnectionHandle() { };

        client.send(largeMessage());
        int fragmentCount = clientTransport.sent.size();
        serverTransport.receive(connection, clientTransport.sent.get(0));
        clock.set(1001L);
        server.tickReliability();
        retryRequest(codec, serverTransport.sent.get(0));
        clientTransport.receive(serverTransport.sent.get(0));

        assertEquals(fragmentCount * 2, clientTransport.sent.size());
        assertFragmentGroupEquals(clientTransport.sent, fragmentCount);
    }

    @Test
    @DisplayName("可靠性层：接收端第二次超时升级重同步，不再发送重发请求")
    void 第二次超时升级重同步() {
        FakeTransport senderTransport = fragmentTransport();
        FakeTransport receiverTransport = fragmentTransport();
        PacketCodec codec = new PacketCodec();
        AtomicLong clock = new AtomicLong(0L);
        List<Integer> receiverResyncs = new ArrayList<>();
        PacketDispatcher sender = dispatcher(senderTransport, codec, clock, new ArrayList<>());
        PacketDispatcher receiver = dispatcher(receiverTransport, codec, clock, receiverResyncs);
        ConnectionHandle connection = new ConnectionHandle() { };

        sender.send(connection, largeMessage());
        int fragmentCount = senderTransport.sent.size();
        receiverTransport.receive(senderTransport.sent.get(0));
        clock.set(1001L);
        receiver.tickReliability();
        senderTransport.receive(connection, receiverTransport.sent.get(0));
        receiverTransport.receive(senderTransport.sent.get(fragmentCount));
        clock.set(2002L);
        receiver.tickReliability();

        assertEquals(1, receiverTransport.sent.size(), "第二次超时不应再请求重发");
        assertEquals(1, receiverResyncs.size());
    }

    @Test
    @DisplayName("可靠性层：发送缓存过期后收到重发请求升级重同步")
    void 发送缓存过期升级重同步() {
        FakeTransport senderTransport = fragmentTransport();
        FakeTransport receiverTransport = fragmentTransport();
        PacketCodec codec = new PacketCodec();
        AtomicLong clock = new AtomicLong(0L);
        List<Integer> senderResyncs = new ArrayList<>();
        PacketDispatcher sender = dispatcher(senderTransport, codec, clock, senderResyncs);
        PacketDispatcher receiver = dispatcher(receiverTransport, codec, clock, new ArrayList<>());
        ConnectionHandle connection = new ConnectionHandle() { };

        sender.send(connection, largeMessage());
        int originalFrames = senderTransport.sent.size();
        receiverTransport.receive(senderTransport.sent.get(0));
        clock.set(1001L);
        receiver.tickReliability();
        clock.set(6000L);
        sender.tickReliability();
        senderTransport.receive(connection, receiverTransport.sent.get(0));

        assertEquals(originalFrames, senderTransport.sent.size(), "过期缓存不得重发");
        assertEquals(1, senderResyncs.size());
    }

    @Test
    @DisplayName("可靠性层：出站缓存容量淘汰旧组，旧组请求升级重同步")
    void 出站缓存容量受限() {
        FakeTransport transport = fragmentTransport();
        PacketCodec codec = new PacketCodec();
        AtomicLong clock = new AtomicLong(0L);
        List<Integer> resyncs = new ArrayList<>();
        PacketDispatcher sender = new PacketDispatcher(
                transport, codec, reliabilityConfig(1), clock::get,
                (connection, seqId) -> resyncs.add(seqId));
        ConnectionHandle connection = new ConnectionHandle() { };

        sender.send(connection, largeMessage());
        int firstSeqId = ((FragmentPacket) codec.decode(transport.sent.get(0))).getSeqId();
        sender.send(connection, largeMessage());
        senderTransportRetry(transport, codec, connection, firstSeqId);

        assertEquals(1, resyncs.size());
        assertEquals(firstSeqId, resyncs.get(0).intValue());
    }

    @Test
    @DisplayName("可靠性层：连接断开同时清理出站缓存与待重组分组")
    void 连接断开清理可靠性状态() {
        FakeTransport transport = fragmentTransport();
        PacketCodec codec = new PacketCodec();
        AtomicLong clock = new AtomicLong(0L);
        List<Integer> resyncs = new ArrayList<>();
        PacketDispatcher dispatcher = dispatcher(transport, codec, clock, resyncs);
        ConnectionHandle connection = new ConnectionHandle() { };

        dispatcher.send(connection, largeMessage());
        FragmentPacket first = (FragmentPacket) codec.decode(transport.sent.get(0));
        transport.receive(connection, transport.sent.get(0));
        dispatcher.onDisconnected(connection);
        clock.set(1001L);
        dispatcher.tickReliability();
        senderTransportRetry(transport, codec, connection, first.getSeqId());

        assertEquals(1, resyncs.size(), "断开后旧重发请求应因无缓存升级重同步");
        assertFalse(transport.sent.stream().anyMatch(frame -> codec.decode(frame).id()
                == PacketIds.FRAGMENT_RETRY_REQUEST), "断开后不得为旧待重组分组发送请求");
    }

    @Test
    @DisplayName("可靠性层：旧连接迟到断线不得清除同标识新物理连接的出站缓存")
    void 旧连接断线不清新连接缓存() {
        FakeTransport transport = fragmentTransport();
        PacketCodec codec = new PacketCodec();
        AtomicLong clock = new AtomicLong(0L);
        List<Integer> resyncs = new ArrayList<>();
        PacketDispatcher dispatcher = dispatcher(transport, codec, clock, resyncs);
        ConnectionHandle oldConnection = new EqualConnection(3);
        ConnectionHandle newConnection = new EqualConnection(3);

        dispatcher.send(newConnection, largeMessage());
        int fragmentCount = transport.sent.size();
        int seqId = ((FragmentPacket) codec.decode(transport.sent.get(0))).getSeqId();
        dispatcher.onDisconnected(oldConnection);
        senderTransportRetry(transport, codec, newConnection, seqId);

        assertEquals(fragmentCount * 2, transport.sent.size());
        assertTrue(resyncs.isEmpty());
    }

    private static FakeTransport fragmentTransport() {
        FakeTransport transport = new FakeTransport();
        transport.maxPayload = 64;
        return transport;
    }

    private static PacketDispatcher dispatcher(
            FakeTransport transport, PacketCodec codec, AtomicLong clock, List<Integer> resyncs) {
        return new PacketDispatcher(
                transport, codec, reliabilityConfig(4), clock::get,
                (connection, seqId) -> resyncs.add(seqId));
    }

    private static PacketDispatcher.ReliabilityConfig reliabilityConfig(int maxCachedGroups) {
        return new PacketDispatcher.ReliabilityConfig(
                1000L, 1000L, 5000L, 64, 64, 4096, maxCachedGroups, 4096);
    }

    private static FragmentRetryRequestPacket retryRequest(PacketCodec codec, byte[] frame) {
        Packet packet = codec.decode(frame);
        assertEquals(PacketIds.FRAGMENT_RETRY_REQUEST, packet.id());
        return (FragmentRetryRequestPacket) packet;
    }

    private static void senderTransportRetry(
            FakeTransport transport, PacketCodec codec, ConnectionHandle connection, int seqId) {
        transport.receive(connection, codec.encode(new FragmentRetryRequestPacket(seqId)));
    }

    private static void deliverRange(FakeTransport receiver, List<byte[]> frames, int from, int to) {
        for (int index = from; index < to; index++) {
            receiver.receive(frames.get(index));
        }
    }

    private static ServerMessagePacket largeMessage() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 500; index++) {
            text.append('A');
        }
        return new ServerMessagePacket(text.toString());
    }

    private static void assertFragmentGroupEquals(List<byte[]> sent, int fragmentCount) {
        for (int index = 0; index < fragmentCount; index++) {
            assertArrayEquals(sent.get(index), sent.get(index + fragmentCount));
        }
    }

    /** equals 相同但对象身份不同的物理连接。 */
    private static final class EqualConnection implements ConnectionHandle {
        private final int id;

        EqualConnection(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualConnection && id == ((EqualConnection) other).id;
        }

        @Override
        public int hashCode() {
            return id;
        }
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
