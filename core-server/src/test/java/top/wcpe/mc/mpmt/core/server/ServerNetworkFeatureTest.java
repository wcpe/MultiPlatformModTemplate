package top.wcpe.mc.mpmt.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.ProtocolVersion;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ClientIdReportPacket;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/**
 * 服务端网络装配特性（FR-19）：经运行时装配 TransportPort → dispatcher → 握手服务 + Ping/Pong 示例，
 * 平台无关、纯 JVM 穷举（同一份装配将在各平台经各自 TransportPort 复用）。
 */
class ServerNetworkFeatureTest {

    private final PacketCodec codec = new PacketCodec();

    private static ConnectionHandle conn() {
        return new ConnectionHandle() {
        };
    }

    /** 启用一个装配好服务端网络的运行时。 */
    private ServerNetworkFeature enabled(FakeServerTransport transport) {
        MpmtRuntime runtime = new MpmtRuntime();
        runtime.ports().register(TransportPort.class, transport);
        AtomicLong seq = new AtomicLong();
        ServerNetworkFeature feature =
                new ServerNetworkFeature(new BanRegistry(), () -> "s-" + seq.incrementAndGet());
        runtime.features().register(feature);
        runtime.enable();
        return feature;
    }

    private Packet lastSent(FakeServerTransport transport) {
        return codec.decode(transport.sends.get(transport.sends.size() - 1));
    }

    @Test
    @DisplayName("装配后：握手（含标识上报欢迎）+ Ping/Pong 往返")
    void 装配握手与往返() {
        FakeServerTransport transport = new FakeServerTransport();
        ServerNetworkFeature feature = enabled(transport);
        ConnectionHandle c = conn();

        // 客户端发兼容 ClientHello → 服务端回 ServerHello(accepted)
        transport.receive(c, codec.encode(new ClientHelloPacket(ProtocolVersion.CURRENT, "1.0.0")));
        ServerHelloPacket hello = (ServerHelloPacket) lastSent(transport);
        assertTrue(hello.isAccepted());

        // 客户端上报标识 → 服务端回欢迎，会话建立
        transport.receive(c, codec.encode(new ClientIdReportPacket("honest")));
        ServerMessagePacket welcome = (ServerMessagePacket) lastSent(transport);
        assertEquals("欢迎", welcome.getText());
        assertEquals(HandshakeStateMachine.State.ESTABLISHED, feature.handshakeService().stateOf(c));

        // 示例往返：Ping → Pong（FR-19 发包示例）
        transport.receive(c, codec.encode(new PingPacket(99L)));
        PongPacket pong = (PongPacket) lastSent(transport);
        assertEquals(99L, pong.getNonce());
    }

    @Test
    @DisplayName("装配产物在启用后可取，启用前取即拒")
    void 装配产物可见性() {
        ServerNetworkFeature feature =
                new ServerNetworkFeature(new BanRegistry(), () -> "s");
        assertThrows(IllegalStateException.class, feature::handshakeService);
        assertThrows(IllegalStateException.class, feature::hudMessageService);

        FakeServerTransport transport = new FakeServerTransport();
        feature = enabled(transport);
        assertNotNull(feature.handshakeService());
        assertNotNull(feature.hudMessageService());
        assertEquals("server-network", feature.name());
    }

    @Test
    @DisplayName("构造入参为空即拒")
    void 入参校验() {
        assertThrows(NullPointerException.class, () -> new ServerNetworkFeature(null, () -> "s"));
        assertThrows(NullPointerException.class, () -> new ServerNetworkFeature(new BanRegistry(), null));
    }

    /** 假服务端传输：记录发送、可注入收到的字节。 */
    private static final class FakeServerTransport implements TransportPort {
        final List<byte[]> sends = new ArrayList<>();
        private BiConsumer<ConnectionHandle, byte[]> receiver;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            sends.add(data);
        }

        @Override
        public void send(byte[] data) {
            throw new UnsupportedOperationException("服务端传输不支持无连接发送");
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            this.receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 32767;
        }

        /** 模拟从某连接收到字节。 */
        void receive(ConnectionHandle connection, byte[] data) {
            receiver.accept(connection, data);
        }
    }
}
