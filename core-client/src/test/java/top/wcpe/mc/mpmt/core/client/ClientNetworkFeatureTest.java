package top.wcpe.mc.mpmt.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.ProtocolVersion;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ClientIdReportPacket;
import top.wcpe.mc.mpmt.protocol.packet.ResyncRequestPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/**
 * 客户端网络装配特性（FR-19）：经运行时装配 TransportPort → dispatcher → 客户端握手服务，
 * 平台无关、纯 JVM 穷举（同一份装配将在各客户端经各自 TransportPort 复用）。
 */
class ClientNetworkFeatureTest {

    private static final String MOD_VERSION = "1.0.0-test";
    private static final String CLIENT_CODE = "honest-code";

    private final PacketCodec codec = new PacketCodec();

    private ClientNetworkFeature enabled(FakeClientTransport transport) {
        MpmtRuntime runtime = new MpmtRuntime();
        runtime.ports().register(TransportPort.class, transport);
        ClientNetworkFeature feature = new ClientNetworkFeature(MOD_VERSION, () -> CLIENT_CODE);
        runtime.features().register(feature);
        runtime.enable();
        return feature;
    }

    private Packet lastSent(FakeClientTransport transport) {
        return codec.decode(transport.sends.get(transport.sends.size() - 1));
    }

    @Test
    @DisplayName("装配后：发起握手发 ClientHello，被接受后上报标识、收服务端消息")
    void 装配握手与上报() {
        FakeClientTransport transport = new FakeClientTransport();
        ClientNetworkFeature feature = enabled(transport);

        // 发起握手 → 发 ClientHello（当前协议版本 + mod 版本）
        feature.startHandshake();
        ClientHelloPacket hello = (ClientHelloPacket) lastSent(transport);
        assertEquals(ProtocolVersion.CURRENT, hello.getProtocolVersion());
        assertEquals(MOD_VERSION, hello.getModVersion());

        // 收兼容 ServerHello(accepted) → 上报弱标识
        transport.receive(codec.encode(new ServerHelloPacket(ProtocolVersion.CURRENT, "sess-1", true)));
        ClientIdReportPacket report = (ClientIdReportPacket) lastSent(transport);
        assertEquals(CLIENT_CODE, report.getClientId());
        assertTrue(feature.handshakeClient().isAccepted());
        assertEquals("sess-1", feature.handshakeClient().sessionId());

        // 收服务端欢迎消息
        transport.receive(codec.encode(new ServerMessagePacket("欢迎")));
        assertEquals("欢迎", feature.handshakeClient().lastServerMessage());
    }

    @Test
    @DisplayName("装配产物启用后可取、启用前取即拒")
    void 装配产物可见性() {
        ClientNetworkFeature feature = new ClientNetworkFeature(MOD_VERSION, () -> CLIENT_CODE);
        assertThrows(IllegalStateException.class, feature::handshakeClient);
        assertThrows(IllegalStateException.class, feature::startHandshake);

        feature = enabled(new FakeClientTransport());
        assertNotNull(feature.handshakeClient());
        assertEquals("client-network", feature.name());
    }

    @Test
    @DisplayName("重连重同步：requestResync 发出携带 sinceRevision 的 ResyncRequest（FR-24）")
    void 重连请求重同步发包() {
        FakeClientTransport transport = new FakeClientTransport();
        ClientNetworkFeature feature = enabled(transport);

        feature.requestResync(987654321L);

        ResyncRequestPacket sent = (ResyncRequestPacket) lastSent(transport);
        assertEquals(987654321L, sent.getSinceRevision());
    }

    @Test
    @DisplayName("构造入参为空即拒")
    void 入参校验() {
        assertThrows(NullPointerException.class, () -> new ClientNetworkFeature(null, () -> CLIENT_CODE));
        assertThrows(NullPointerException.class, () -> new ClientNetworkFeature(MOD_VERSION, null));
    }

    /** 假客户端传输：记录无连接发送、可注入服务端来的字节。 */
    private static final class FakeClientTransport implements TransportPort {
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

        /** 模拟收到服务端字节（携带服务端连接哨兵句柄）。 */
        void receive(byte[] data) {
            receiver.accept(SERVER, data);
        }
    }
}
