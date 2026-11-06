package top.wcpe.mc.mpmt.platform.bukkit.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import top.wcpe.mc.mpmt.kernel.port.ConnectionHandle;
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
        BukkitServerTransport transport = new BukkitServerTransport(network);
        // 收包回调未设、但完成通知已设：完成通知必须也不被触发。
        // handle() 里 receiveHandler == null 时直接 return，不会走到 handledCallback。
        AtomicReference<ConnectionHandle> handled = new AtomicReference<>();
        transport.onHandled(handled::set);

        network.onPluginMessageReceived(CHANNEL, server.addPlayer(), new byte[] {9});

        assertNull(handled.get(), "未设收包回调时包应被丢弃，不应触发任何上层处理");
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

        BukkitConnectionHandle handle = connections.connected(player);
        transport.send(handle, new byte[] {7, 7});

        // MockBukkit 不记录 sendPluginMessage，所以正向「确实发出」不可直接观察；
        // 但 send() 的守卫链是「handle 仍是该玩家的当前连接」，这个前提可以断言——
        // 它如果为假，上面的发送就是被静默丢弃的，测试却依然会绿。
        assertTrue(
                connections.isCurrent(handle, player),
                "句柄应仍是该玩家的当前连接，否则发送路径实际走的是丢弃分支");
    }

    @Test
    void 连接被替换后旧句柄发送被丢弃() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        BukkitConnectionRegistry connections = new BukkitConnectionRegistry();
        BukkitServerTransport transport = create(plugin, connections);
        PlayerMock player = server.addPlayer();

        BukkitConnectionHandle stale = connections.connected(player);
        // 重新登录 → 生成新句柄，旧句柄失效
        BukkitConnectionHandle current = connections.connected(player);

        assertFalse(connections.isCurrent(stale, player), "旧句柄应已失效");
        assertTrue(connections.isCurrent(current, player), "新句柄应生效");

        // 用失效句柄发送：不得影响新连接（这正是 send() 里 isCurrent 守卫的意义）
        transport.send(stale, new byte[] {7, 7});
        assertTrue(connections.isCurrent(current, player), "失效句柄发送不应改动当前连接");
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
