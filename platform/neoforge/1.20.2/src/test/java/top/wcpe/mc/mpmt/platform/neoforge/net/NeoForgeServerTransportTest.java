package top.wcpe.mc.mpmt.platform.neoforge.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.platform.neoforge.NeoForgeTestSupport;
import top.wcpe.mc.mpmt.platform.neoforge.version.NeoForgeServerNetwork;

/**
 * NeoForge 传输适配（L3）：委托 L4 适配器并管理连接表。
 *
 * <p>L4 适配器用动态代理替身，只实现本层真正依赖的契约；连接句柄由替身按真实语义返回。
 */
class NeoForgeServerTransportTest {

    @Test
    @DisplayName("空 L4 适配器被拒绝")
    void 空适配器被拒绝() {
        assertThrows(NullPointerException.class, () -> new NeoForgeServerTransport(null));
    }

    @Test
    @DisplayName("同一原生玩家多次取句柄复用同一实例")
    void 句柄按玩家对象复用() {
        FakeNetwork network = new FakeNetwork();
        NeoForgeServerTransport transport = new NeoForgeServerTransport(network.proxy());
        ServerPlayer player = NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "复用");

        NeoForgeConnectionHandle first = transport.onConnected(player);
        NeoForgeConnectionHandle second = transport.onConnected(player);
        NeoForgeConnectionHandle third = transport.connectionFor(player);

        assertSame(first, second);
        assertSame(first, third);
        assertEquals(1, network.connectionCalls);
    }

    @Test
    @DisplayName("同 UUID 的新玩家对象替换旧句柄（重连语义）")
    void 重连替换句柄() {
        FakeNetwork network = new FakeNetwork();
        NeoForgeServerTransport transport = new NeoForgeServerTransport(network.proxy());
        UUID playerId = UUID.randomUUID();
        ServerPlayer first = NeoForgeTestSupport.newPlayer(playerId, "首连");
        ServerPlayer reconnected = NeoForgeTestSupport.newPlayer(playerId, "重连");

        NeoForgeConnectionHandle oldHandle = transport.onConnected(first);
        NeoForgeConnectionHandle newHandle = transport.onConnected(reconnected);

        assertNotSame(oldHandle, newHandle, "新对象应替换旧句柄");
        assertSame(reconnected, newHandle.player());
        assertNull(transport.onDisconnected(first), "旧对象已非当前连接，登出应无效果");
        assertEquals(newHandle, transport.onDisconnected(reconnected));
    }

    @Test
    @DisplayName("未登记的玩家登出返回空")
    void 未登记登出返回空() {
        NeoForgeServerTransport transport =
                new NeoForgeServerTransport(new FakeNetwork().proxy());

        assertNull(transport.onDisconnected(
                NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "陌生")));
    }

    @Test
    @DisplayName("清空连接表后所有玩家登出均返回空")
    void 清空连接表() {
        FakeNetwork network = new FakeNetwork();
        NeoForgeServerTransport transport = new NeoForgeServerTransport(network.proxy());
        ServerPlayer player = NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "待清");
        transport.onConnected(player);

        transport.clearConnections();

        assertNull(transport.onDisconnected(player));
    }

    @Test
    @DisplayName("向连接发送委托给 L4 适配器并解封原生玩家")
    void 发送委托适配器() {
        FakeNetwork network = new FakeNetwork();
        NeoForgeServerTransport transport = new NeoForgeServerTransport(network.proxy());
        ServerPlayer player = NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "发送");

        transport.send(transport.onConnected(player), new byte[] {1, 2});

        assertSame(player, network.lastSentPlayer);
        assertEquals(1, network.sent.size());
        assertEquals(2, network.sent.get(0).length);
    }

    @Test
    @DisplayName("服务端传输不支持无连接发送")
    void 无连接发送被拒绝() {
        NeoForgeServerTransport transport =
                new NeoForgeServerTransport(new FakeNetwork().proxy());

        assertThrows(UnsupportedOperationException.class, () -> transport.send(new byte[] {1}));
    }

    @Test
    @DisplayName("收包回调把原生玩家转换为稳定句柄")
    void 收包回调转换句柄() {
        FakeNetwork network = new FakeNetwork();
        NeoForgeServerTransport transport = new NeoForgeServerTransport(network.proxy());
        List<ConnectionHandle> handles = new ArrayList<>();
        List<byte[]> payloads = new ArrayList<>();
        transport.onReceive((connection, data) -> {
            handles.add(connection);
            payloads.add(data);
        });
        ServerPlayer player = NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "入站");

        network.deliver(player, new byte[] {7});

        assertEquals(1, handles.size());
        assertTrue(handles.get(0) instanceof NeoForgeConnectionHandle);
        assertSame(player, ((NeoForgeConnectionHandle) handles.get(0)).player());
        assertEquals(1, payloads.get(0).length);

        network.deliver(player, new byte[] {8});
        assertSame(handles.get(0), handles.get(1), "同一玩家应收敛到同一句柄");
    }

    @Test
    @DisplayName("空收包器被拒绝")
    void 空收包器被拒绝() {
        NeoForgeServerTransport transport =
                new NeoForgeServerTransport(new FakeNetwork().proxy());

        assertThrows(NullPointerException.class, () -> transport.onReceive(null));
    }

    @Test
    @DisplayName("单包上限与客户端收包器委托给 L4 适配器")
    void 上限与客户端收包器委托() {
        FakeNetwork network = new FakeNetwork();
        NeoForgeServerTransport transport = new NeoForgeServerTransport(network.proxy());

        assertEquals(FakeNetwork.MAX_PAYLOAD, transport.maxPayloadSize());
        transport.setClientReceiver(data -> payloads(network));
        assertNotNull(network.clientReceiver);

        transport.sendToServer(new byte[] {5});
        assertEquals(1, network.serverBound.size());
    }

    @Test
    @DisplayName("客户端传输拒绝有连接发送并转发无连接发送")
    void 客户端传输语义() {
        FakeNetwork network = new FakeNetwork();
        NeoForgeServerTransport serverTransport = new NeoForgeServerTransport(network.proxy());
        NeoForgeClientTransport clientTransport = new NeoForgeClientTransport(serverTransport);

        assertThrows(UnsupportedOperationException.class,
                () -> clientTransport.send(null, new byte[] {1}));

        List<byte[]> received = new ArrayList<>();
        clientTransport.onReceive((handle, data) -> received.add(data));
        assertNotNull(network.clientReceiver);
        network.clientReceiver.accept(new byte[] {9});
        assertEquals(1, received.size());

        clientTransport.send(new byte[] {2});
        assertEquals(1, network.serverBound.size());
        assertEquals(FakeNetwork.MAX_PAYLOAD, clientTransport.maxPayloadSize());
        assertNotNull(clientTransport.serverConnection());
        assertSame(clientTransport.serverConnection(), clientTransport.serverConnection());
    }

    @Test
    @DisplayName("客户端传输拒绝空传输与空收包器")
    void 客户端传输空值防御() {
        assertThrows(NullPointerException.class, () -> new NeoForgeClientTransport(null));

        NeoForgeClientTransport clientTransport = new NeoForgeClientTransport(
                new NeoForgeServerTransport(new FakeNetwork().proxy()));
        assertThrows(NullPointerException.class, () -> clientTransport.onReceive(null));
    }

    @Test
    @DisplayName("传输端口契约在服务端与客户端实现间保持一致")
    void 传输契约一致() {
        assertTrue(TransportPort.class.isAssignableFrom(NeoForgeServerTransport.class));
        assertTrue(TransportPort.class.isAssignableFrom(NeoForgeClientTransport.class));
    }

    @Test
    @DisplayName("连接句柄按玩家 UUID 判等并暴露玩家标识")
    void 连接句柄判等() {
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = NeoForgeTestSupport.newPlayer(playerId, "句柄");
        NeoForgeConnectionHandle handle = new NeoForgeConnectionHandle(player);
        NeoForgeConnectionHandle same = new NeoForgeConnectionHandle(player);

        assertEquals(handle, handle);
        assertEquals(handle, same);
        assertEquals(handle.hashCode(), same.hashCode());
        assertFalse(handle.equals("其它类型"));
        assertSame(player, handle.player());
        assertEquals(playerId, handle.playerId());
        assertThrows(NullPointerException.class, () -> new NeoForgeConnectionHandle(null));
    }

    private static void payloads(FakeNetwork network) {
        // 客户端收包器被登记即可，无需额外动作
        assertNotNull(network);
    }

    /** L4 适配器替身：记录发送与回调，其余契约返回稳定缺省值。 */
    private static final class FakeNetwork {

        static final int MAX_PAYLOAD = 1_048_576;

        private final List<byte[]> sent = new ArrayList<>();
        private final List<byte[]> serverBound = new ArrayList<>();
        private ServerPlayer lastSentPlayer;
        private BiConsumer<ServerPlayer, byte[]> receiver;
        private Consumer<byte[]> clientReceiver;
        private int connectionCalls;

        NeoForgeServerNetwork proxy() {
            return (NeoForgeServerNetwork) Proxy.newProxyInstance(
                    NeoForgeServerNetwork.class.getClassLoader(),
                    new Class<?>[] {NeoForgeServerNetwork.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "maxPayloadSize":
                                return MAX_PAYLOAD;
                            case "connectionOf":
                                connectionCalls++;
                                return new NeoForgeConnectionHandle((ServerPlayer) args[0]);
                            case "registerReceiver":
                                receiver = asBiConsumer(args[0]);
                                return null;
                            case "setClientReceiver":
                                clientReceiver = asConsumer(args[0]);
                                return null;
                            case "sendToServer":
                                serverBound.add((byte[]) args[0]);
                                return null;
                            case "send":
                                lastSentPlayer = (ServerPlayer) args[0];
                                sent.add((byte[]) args[1]);
                                return null;
                            default:
                                throw new UnsupportedOperationException("未替身化的方法：" + method.getName());
                        }
                    });
        }

        void deliver(ServerPlayer player, byte[] data) {
            receiver.accept(player, data);
        }

        @SuppressWarnings("unchecked")
        private static BiConsumer<ServerPlayer, byte[]> asBiConsumer(Object value) {
            return (BiConsumer<ServerPlayer, byte[]>) value;
        }

        @SuppressWarnings("unchecked")
        private static Consumer<byte[]> asConsumer(Object value) {
            return (Consumer<byte[]>) value;
        }
    }
}
