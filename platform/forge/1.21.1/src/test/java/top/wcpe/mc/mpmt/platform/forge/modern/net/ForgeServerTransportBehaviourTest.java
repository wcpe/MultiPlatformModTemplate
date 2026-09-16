package top.wcpe.mc.mpmt.platform.forge.modern.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.forge.modern.Forge121MinecraftTestSupport;

/** Forge 1.21.1 服务端传输：连接句柄表、收包转接与版本敏感上限的真实行为。 */
class ForgeServerTransportBehaviourTest {

    @Test
    @DisplayName("无连接发送不被支持，单包上限取自类型化通道")
    void 无连接发送被拒() {
        ForgeServerTransport transport = new ForgeServerTransport(Forge121MinecraftTestSupport.newTypedChannel());

        assertEquals(ForgeTypedPayloadChannel.MAX_PAYLOAD_SIZE, transport.maxPayloadSize());
        UnsupportedOperationException error =
                assertThrows(UnsupportedOperationException.class, () -> transport.send(new byte[] {1}));
        assertEquals("服务端传输不支持无连接发送", error.getMessage());
    }

    @Test
    @DisplayName("同一玩家对象复用句柄，断开后清除")
    void 句柄按玩家对象复用与清除() {
        ForgeServerTransport transport = new ForgeServerTransport(Forge121MinecraftTestSupport.newTypedChannel());
        ServerPlayer player = Forge121MinecraftTestSupport.newPlayer(UUID.randomUUID(), "传输玩家");

        ForgeConnectionHandle first = transport.onConnected(player);
        assertSame(first, transport.onConnected(player));
        assertSame(player, first.player());

        assertSame(first, transport.onDisconnected(player));
        assertNull(transport.onDisconnected(player));

        ForgeConnectionHandle recreated = transport.onConnected(player);
        assertNotSame(first, recreated);
        assertSame(player, recreated.player());
        transport.clearConnections();
        assertNotSame(recreated, transport.onConnected(player));
    }

    @Test
    @DisplayName("同 UUID 不同原生对象各自持有独立句柄")
    void 原生对象身份区分句柄() {
        ForgeServerTransport transport = new ForgeServerTransport(Forge121MinecraftTestSupport.newTypedChannel());
        UUID playerId = UUID.randomUUID();
        ServerPlayer first = Forge121MinecraftTestSupport.newPlayer(playerId, "首次");
        ServerPlayer second = Forge121MinecraftTestSupport.newPlayer(playerId, "重连");

        ForgeConnectionHandle left = transport.onConnected(first);
        ForgeConnectionHandle right = transport.onConnected(second);

        assertNotSame(left, right);
        assertNotSame(left.player(), right.player());
        assertSame(first, left.player());
        assertSame(second, right.player());
    }

    @Test
    @DisplayName("收包器为空时明确失败")
    void 收包器为空被拒() {
        ForgeServerTransport transport = new ForgeServerTransport(Forge121MinecraftTestSupport.newTypedChannel());

        assertThrows(NullPointerException.class, () -> transport.onReceive(null));
    }

    @Test
    @DisplayName("构造拒绝空通道")
    void 构造拒绝空通道() {
        assertThrows(NullPointerException.class, () -> new ForgeServerTransport(null));
    }

    @Test
    @DisplayName("客户端传输拒绝跨连接形态与空收包器，未连接时发送明确失败")
    void 客户端传输边界() {
        Connection connection = Forge121MinecraftTestSupport.newClientConnection();
        ForgeClientTransport transport = new ForgeClientTransport(
                Forge121MinecraftTestSupport.newTypedChannel(), connection);

        assertEquals(ForgeTypedPayloadChannel.MAX_PAYLOAD_SIZE, transport.maxPayloadSize());
        UnsupportedOperationException error = assertThrows(
                UnsupportedOperationException.class, () -> transport.send(null, new byte[] {1}));
        assertEquals("客户端传输只支持向当前服务端发送", error.getMessage());
        assertThrows(NullPointerException.class, () -> transport.onReceive(null));
        assertThrows(NullPointerException.class, () -> new ForgeClientTransport(null, connection));
        assertThrows(NullPointerException.class, () -> new ForgeClientTransport(
                Forge121MinecraftTestSupport.newTypedChannel(), null));

        // 未完成握手时 Forge 通道拒绝发送
        assertThrows(RuntimeException.class, () -> transport.send(new byte[] {1}));
    }

    @Test
    @DisplayName("客户端传输注册后可清除客户端收包器")
    void 客户端收包器注册与清除() {
        ForgeClientTransport transport = new ForgeClientTransport(
                Forge121MinecraftTestSupport.newTypedChannel(), Forge121MinecraftTestSupport.newClientConnection());

        transport.onReceive((connection, data) -> { });
        transport.clearReceiver();

        assertTrue(transport.maxPayloadSize() > 0);
    }
}
