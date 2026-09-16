package top.wcpe.mc.mpmt.platform.forge.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.platform.forge.ForgeMinecraftTestSupport;
import top.wcpe.mc.mpmt.platform.forge.ForgeMinecraftTestSupport.FakeServerNetwork;

/** Forge 服务端传输：连接表、收发委托与版本敏感上限的真实行为。 */
class ForgeServerTransportTest {

    @Test
    @DisplayName("无连接发送不被支持，通道标识与单包上限委托给 L4 适配器")
    void 委托通道与上限() {
        FakeServerNetwork network = new FakeServerNetwork();
        ForgeServerTransport transport = new ForgeServerTransport(network);

        assertEquals(network.channelId(), transport.channelId());
        assertEquals(4096, transport.maxPayloadSize());
        UnsupportedOperationException error =
                assertThrows(UnsupportedOperationException.class, () -> transport.send(new byte[] {1}));
        assertEquals("服务端传输不支持无连接发送", error.getMessage());
    }

    @Test
    @DisplayName("同一玩家对象复用句柄、发送经句柄解封投递到 L4 适配器")
    void 发送经句柄解封() {
        FakeServerNetwork network = new FakeServerNetwork();
        ForgeServerTransport transport = new ForgeServerTransport(network);
        ServerPlayer player = ForgeMinecraftTestSupport.newPlayer(UUID.randomUUID(), "传输玩家");

        ForgeConnectionHandle first = transport.onConnected(player);
        ForgeConnectionHandle again = transport.connectionFor(player);
        assertSame(first, again);
        assertEquals(player.getUUID(), first.playerId());
        assertSame(player, first.player());

        transport.send(first, new byte[] {1, 2, 3});

        List<byte[]> sent = network.sentPayloads();
        assertEquals(1, sent.size());
        assertArrayEquals(new byte[] {1, 2, 3}, sent.get(0));
    }

    @Test
    @DisplayName("同 UUID 但不同原生对象视为新物理连接，旧句柄不复用")
    void 原生对象身份决定句柄复用() {
        FakeServerNetwork network = new FakeServerNetwork();
        ForgeServerTransport transport = new ForgeServerTransport(network);
        UUID playerId = UUID.randomUUID();
        ServerPlayer first = ForgeMinecraftTestSupport.newPlayer(playerId, "首次登录");
        ServerPlayer reconnected = ForgeMinecraftTestSupport.newPlayer(playerId, "重连");

        ForgeConnectionHandle original = transport.onConnected(first);
        ForgeConnectionHandle replaced = transport.connectionFor(reconnected);

        assertNotSame(original, replaced);
        assertSame(reconnected, replaced.player());
        assertSame(replaced, transport.connectionFor(reconnected));
    }

    @Test
    @DisplayName("退出仅移除同一原生对象的连接，驱动报文可回落到 L3 receivers")
    void 断开与入站分发() {
        FakeServerNetwork network = new FakeServerNetwork();
        ForgeServerTransport transport = new ForgeServerTransport(network);
        ServerPlayer player = ForgeMinecraftTestSupport.newPlayer(UUID.randomUUID(), "入站玩家");
        ServerPlayer other = ForgeMinecraftTestSupport.newPlayer(UUID.randomUUID(), "无关玩家");

        transport.onConnected(player);
        assertNull(transport.onDisconnected(other));
        ForgeConnectionHandle removed = transport.onDisconnected(player);
        assertEquals(player.getUUID(), removed.playerId());
        assertNull(transport.onDisconnected(player));

        List<ConnectionHandle> senders = new ArrayList<>();
        List<byte[]> payloads = new ArrayList<>();
        transport.onReceive((connection, data) -> {
            senders.add(connection);
            payloads.add(data);
        });
        network.receive(player, new byte[] {9});

        assertEquals(1, senders.size());
        assertSame(player, ((ForgeConnectionHandle) senders.get(0)).player());
        assertArrayEquals(new byte[] {9}, payloads.get(0));

        transport.clearConnections();
        ForgeConnectionHandle afterClear = transport.onConnected(other);
        assertSame(other, afterClear.player());
    }

    @Test
    @DisplayName("空网络适配器被拒绝")
    void 拒绝空网络适配器() {
        assertThrows(NullPointerException.class, () -> new ForgeServerTransport(null));
    }
}
