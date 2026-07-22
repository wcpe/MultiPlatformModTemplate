package top.wcpe.mc.mpmt.platform.bukkit.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.platform.bukkit.version.v1_20.V1_20BukkitServerNetwork;

/** Bukkit 1.20.1 网络适配器与版本无关 TransportPort 的协作测试。 */
class BukkitServerTransportTest {

    private static final String CHANNEL = "mpmt:main";

    @AfterEach
    void 拆除Mock() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void 构造后注册收发插件通道() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();

        create(plugin, new BukkitConnectionRegistry());

        Messenger messenger = server.getMessenger();
        assertTrue(messenger.isOutgoingChannelRegistered(plugin, CHANNEL), "出站通道应已注册");
        assertTrue(messenger.isIncomingChannelRegistered(plugin, CHANNEL), "入站通道应已注册");
    }

    @Test
    void 收到插件消息回调上层并带连接句柄() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        BukkitConnectionRegistry connections = new BukkitConnectionRegistry();
        V1_20BukkitServerNetwork network = network(plugin, connections);
        BukkitServerTransport transport = new BukkitServerTransport(network);
        PlayerMock player = server.addPlayer();
        AtomicReference<byte[]> gotData = new AtomicReference<>();
        AtomicReference<ConnectionHandle> gotConn = new AtomicReference<>();
        AtomicReference<ConnectionHandle> handledConn = new AtomicReference<>();
        transport.onHandled(handledConn::set);
        transport.onReceive((conn, data) -> {
            gotConn.set(conn);
            gotData.set(data);
        });

        byte[] payload = {1, 2, 3, 4};
        network.onPluginMessageReceived(CHANNEL, player, payload);

        assertArrayEquals(payload, gotData.get());
        assertEquals(connections.handleOf(player), gotConn.get());
        assertSame(gotConn.get(), handledConn.get(), "完成通知应复用同一物理连接句柄");
    }

    @Test
    void 未设收包回调时丢弃不报错() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        V1_20BukkitServerNetwork network = network(plugin, new BukkitConnectionRegistry());
        new BukkitServerTransport(network);

        network.onPluginMessageReceived(CHANNEL, server.addPlayer(), new byte[] {9});
    }

    @Test
    void 非产品通道不回调上层() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        V1_20BukkitServerNetwork network = network(plugin, new BukkitConnectionRegistry());
        BukkitServerTransport transport = new BukkitServerTransport(network);
        AtomicBoolean called = new AtomicBoolean(false);
        transport.onReceive((conn, data) -> called.set(true));

        network.onPluginMessageReceived("other:channel", server.addPlayer(), new byte[] {1});

        assertFalse(called.get(), "非产品通道不应触发上层回调");
    }

    @Test
    void 向当前连接发送插件消息不抛异常() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        BukkitConnectionRegistry connections = new BukkitConnectionRegistry();
        BukkitServerTransport transport = create(plugin, connections);
        PlayerMock player = server.addPlayer();

        transport.send(connections.connected(player), new byte[] {7, 7});
    }

    @Test
    void 服务端不支持无连接发送() {
        MockBukkit.mock();
        BukkitServerTransport transport =
                create(MockBukkit.createMockPlugin(), new BukkitConnectionRegistry());

        assertThrows(UnsupportedOperationException.class, () -> transport.send(new byte[0]));
    }

    @Test
    void 单包上限取Bukkit插件消息上限() {
        MockBukkit.mock();
        BukkitServerTransport transport =
                create(MockBukkit.createMockPlugin(), new BukkitConnectionRegistry());

        assertEquals(Messenger.MAX_MESSAGE_SIZE, transport.maxPayloadSize());
    }

    private static BukkitServerTransport create(
            MockPlugin plugin, BukkitConnectionRegistry connections) {
        return new BukkitServerTransport(network(plugin, connections));
    }

    private static V1_20BukkitServerNetwork network(
            MockPlugin plugin, BukkitConnectionRegistry connections) {
        return new V1_20BukkitServerNetwork(plugin, connections, CHANNEL);
    }
}
