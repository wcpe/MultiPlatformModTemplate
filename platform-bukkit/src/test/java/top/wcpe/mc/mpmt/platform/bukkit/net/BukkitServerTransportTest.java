package top.wcpe.mc.mpmt.platform.bukkit.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * Bukkit 服务端传输（插件消息）单元测试（MockBukkit，无需真实服）。
 *
 * <p>穷举：收发通道注册 / 网络线程收包回上层 / 错通道与无回调的兜底 / 无连接发送拒绝 / 单包上限。
 * 真实跨端字节往返（Paper 服 ↔ 真实客户端）为实机维度，留 realserver harness（Round B）。
 */
class BukkitServerTransportTest {

    /** 产品跨端通道，须与各平台一致（Fabric 亦为 {@code mpmt:main}）以支持异构互通。 */
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

        new BukkitServerTransport(plugin);

        Messenger messenger = server.getMessenger();
        assertTrue(messenger.isOutgoingChannelRegistered(plugin, CHANNEL), "出站通道应已注册");
        assertTrue(messenger.isIncomingChannelRegistered(plugin, CHANNEL), "入站通道应已注册");
    }

    @Test
    void 收到插件消息回调上层并带连接句柄() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        BukkitServerTransport transport = new BukkitServerTransport(plugin);
        PlayerMock player = server.addPlayer();

        AtomicReference<byte[]> gotData = new AtomicReference<>();
        AtomicReference<ConnectionHandle> gotConn = new AtomicReference<>();
        transport.onReceive(
                (conn, data) -> {
                    gotConn.set(conn);
                    gotData.set(data);
                });

        byte[] payload = {1, 2, 3, 4};
        transport.onPluginMessageReceived(CHANNEL, player, payload);

        assertArrayEquals(payload, gotData.get());
        assertEquals(new BukkitConnectionHandle(player), gotConn.get(), "句柄应按 UUID 标识该玩家");
    }

    @Test
    void 未设收包回调时丢弃不报错() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        BukkitServerTransport transport = new BukkitServerTransport(plugin);
        PlayerMock player = server.addPlayer();

        // 未调 onReceive 即收到消息：应安全丢弃，不抛异常
        transport.onPluginMessageReceived(CHANNEL, player, new byte[] {9});
    }

    @Test
    void 非产品通道不回调上层() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        BukkitServerTransport transport = new BukkitServerTransport(plugin);
        PlayerMock player = server.addPlayer();

        AtomicBoolean called = new AtomicBoolean(false);
        transport.onReceive((conn, data) -> called.set(true));

        transport.onPluginMessageReceived("other:channel", player, new byte[] {1});

        assertFalse(called.get(), "非产品通道不应触发上层回调");
    }

    @Test
    void 向连接发送插件消息不抛异常() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        BukkitServerTransport transport = new BukkitServerTransport(plugin);
        PlayerMock player = server.addPlayer();

        // 出站通道已注册，向玩家发送插件消息应成立（真实字节投递为实机维度）
        transport.send(new BukkitConnectionHandle(player), new byte[] {7, 7});
    }

    @Test
    void 服务端不支持无连接发送() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        BukkitServerTransport transport = new BukkitServerTransport(plugin);

        assertThrows(UnsupportedOperationException.class, () -> transport.send(new byte[0]));
    }

    @Test
    void 单包上限取Bukkit插件消息上限() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        BukkitServerTransport transport = new BukkitServerTransport(plugin);

        assertEquals(Messenger.MAX_MESSAGE_SIZE, transport.maxPayloadSize());
    }
}
