package top.wcpe.mc.mpmt.protocol.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.packet.ResyncRequestPacket;

/** 重同步协调：客户端请求发包、服务端收请求调处理器（FR-24）。 */
class ResyncCoordinatorTest {

    private final PacketCodec codec = new PacketCodec();

    @Test
    @DisplayName("客户端 requestResync 发出 ResyncRequest（携带修订号）")
    void 客户端请求() {
        FakeTransport transport = new FakeTransport();
        PacketDispatcher dispatcher = new PacketDispatcher(transport, codec);
        ResyncCoordinator coordinator = new ResyncCoordinator(dispatcher, (connection, revision) -> { });

        coordinator.requestResync(987654321L);

        ResyncRequestPacket sent = (ResyncRequestPacket) codec.decode(transport.lastClientSend);
        assertEquals(987654321L, sent.getSinceRevision());
    }

    @Test
    @DisplayName("服务端收到 ResyncRequest 调处理器（携带连接与修订号）")
    void 服务端处理() {
        FakeTransport transport = new FakeTransport();
        PacketDispatcher dispatcher = new PacketDispatcher(transport, codec);
        ConnectionHandle[] capturedConn = {null};
        long[] capturedRevision = {-1L};
        new ResyncCoordinator(
                dispatcher,
                (connection, revision) -> {
                    capturedConn[0] = connection;
                    capturedRevision[0] = revision;
                });

        ConnectionHandle conn = new ConnectionHandle() { };
        transport.receive(conn, codec.encode(new ResyncRequestPacket(555L)));

        assertSame(conn, capturedConn[0]);
        assertEquals(555L, capturedRevision[0]);
    }

    @Test
    @DisplayName("构造入参为空即拒")
    void 入参校验() {
        FakeTransport transport = new FakeTransport();
        PacketDispatcher dispatcher = new PacketDispatcher(transport, codec);
        assertThrows(NullPointerException.class, () -> new ResyncCoordinator(null, (c, r) -> { }));
        assertThrows(NullPointerException.class, () -> new ResyncCoordinator(dispatcher, null));
    }

    /** 假传输：记录客户端无连接发送、可注入收到的字节。 */
    private static final class FakeTransport implements TransportPort {
        byte[] lastClientSend;
        private BiConsumer<ConnectionHandle, byte[]> receiver;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            // 本测试不走服务端寻址发送
        }

        @Override
        public void send(byte[] data) {
            lastClientSend = data;
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            this.receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 32767;
        }

        void receive(ConnectionHandle connection, byte[] data) {
            receiver.accept(connection, data);
        }
    }
}
