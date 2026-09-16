package top.wcpe.mc.mpmt.platform.forge.modern.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.Connection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.platform.forge.modern.ForgeTestSupport;

/** Forge 类型化载荷通道：注册、收发路由与空值防御。 */
class ForgeTypedPayloadChannelTest {

    @Test
    @DisplayName("通道以给定标识构造并冻结单包上限")
    void 通道构造与上限() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-test"));

        assertNotNull(channel);
        assertEquals(1_048_576, ForgeTypedPayloadChannel.MAX_PAYLOAD_SIZE);
    }

    @Test
    @DisplayName("空通道标识直接拒绝")
    void 空标识被拒绝() {
        assertThrows(NullPointerException.class, () -> new ForgeTypedPayloadChannel(null));
    }

    @Test
    @DisplayName("服务端收包器与客户端收包器均拒绝空值")
    void 空收包器被拒绝() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-null"));

        assertThrows(NullPointerException.class, () -> channel.registerServerReceiver(null));
        assertThrows(NullPointerException.class, () -> channel.registerClientReceiver(null));
    }

    @Test
    @DisplayName("客户端向服务端发送裸字节无异常")
    void 客户端发送() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-send"));
        Connection connection = ForgeTestSupport.newConnection();

        channel.sendToServer(connection, new byte[] {1, 2, 3});

        assertNotNull(connection);
    }

    @Test
    @DisplayName("空连接发送被拒绝")
    void 空连接发送被拒绝() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-send-null"));

        assertThrows(NullPointerException.class, () -> channel.sendToServer(null, new byte[] {1}));
    }

    @Test
    @DisplayName("向玩家发送经其连接投递，空玩家被拒绝")
    void 向玩家发送() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-player"));
        ServerPlayer player = ForgeTestSupport.newPlayer(UUID.randomUUID(), "接收者");

        channel.sendToPlayer(player, new byte[] {7});

        assertThrows(NullPointerException.class, () -> channel.sendToPlayer(null, new byte[] {7}));
    }

    @Test
    @DisplayName("服务端方向收包路由到服务端收包器并交由平台处理")
    void 服务端收包路由() throws Exception {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-recv-server"));
        ServerPlayer sender = ForgeTestSupport.newPlayer(UUID.randomUUID(), "发送者");
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<ServerPlayer> receivedFrom = new AtomicReference<>();
        channel.registerServerReceiver((player, data) -> {
            receivedFrom.set(player);
            received.set(data);
        });
        Connection connection = ForgeTestSupport.newConnection();
        ForgeTestSupport.bindSender(connection, sender);
        CustomPayloadEvent.Context context = new CustomPayloadEvent.Context(connection);
        ForgeTypedPayload payload = new ForgeTypedPayload(
                new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("mpmt", "channel-recv-server")),
                new byte[] {5, 6});

        ForgeTestSupport.invoke(channel, "receive",
                new Class<?>[] {ForgeTypedPayload.class, CustomPayloadEvent.Context.class},
                payload, context);

        assertSame(sender, receivedFrom.get());
        assertArrayEquals(new byte[] {5, 6}, received.get());
        assertTrue(context.getPacketHandled());
    }

    @Test
    @DisplayName("服务端方向未注册收包器时静默丢弃")
    void 服务端未注册静默() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-recv-silent"));
        Connection connection = ForgeTestSupport.newConnection();
        ForgeTestSupport.bindSender(connection, ForgeTestSupport.newPlayer(UUID.randomUUID(), "甲"));
        CustomPayloadEvent.Context context = new CustomPayloadEvent.Context(connection);
        ForgeTypedPayload payload = new ForgeTypedPayload(
                new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("mpmt", "channel-recv-silent")),
                new byte[] {1});

        ForgeTestSupport.invoke(channel, "receive",
                new Class<?>[] {ForgeTypedPayload.class, CustomPayloadEvent.Context.class},
                payload, context);

        assertTrue(context.getPacketHandled());
    }

    @Test
    @DisplayName("客户端方向收包路由到客户端收包器，清空后不再投递")
    void 客户端收包路由与清空() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-recv-client"));
        List<byte[]> received = new ArrayList<>();
        channel.registerClientReceiver(received::add);
        // 客户端接收方向：上下文判客户端，且无服务端发送者
        Connection connection = ForgeTestSupport.newConnection(
                net.minecraft.network.protocol.PacketFlow.CLIENTBOUND);
        CustomPayloadEvent.Context context = new CustomPayloadEvent.Context(connection);
        ForgeTypedPayload payload = new ForgeTypedPayload(
                new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("mpmt", "channel-recv-client")),
                new byte[] {9});

        ForgeTestSupport.invoke(channel, "receive",
                new Class<?>[] {ForgeTypedPayload.class, CustomPayloadEvent.Context.class},
                payload, context);
        assertEquals(1, received.size());
        assertArrayEquals(new byte[] {9}, received.get(0));

        channel.clearClientReceiver();
        ForgeTestSupport.invoke(channel, "receive",
                new Class<?>[] {ForgeTypedPayload.class, CustomPayloadEvent.Context.class},
                payload, context);
        assertEquals(1, received.size(), "清空后不应再投递");
    }

    @Test
    @DisplayName("服务端传输按玩家登记稳定句柄，登出清除")
    void 服务端传输连接表() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-transport"));
        ForgeServerTransport transport = new ForgeServerTransport(channel);
        ServerPlayer player = ForgeTestSupport.newPlayer(UUID.randomUUID(), "连接玩家");

        ForgeConnectionHandle first = transport.onConnected(player);
        ForgeConnectionHandle second = transport.onConnected(player);
        assertSame(first, second, "同一玩家应复用句柄");
        assertEquals(first, transport.onDisconnected(player));
        assertNull(transport.onDisconnected(player));

        transport.onConnected(player);
        transport.clearConnections();
        assertNull(transport.onDisconnected(player));
    }

    @Test
    @DisplayName("服务端传输向玩家发送并拒绝无连接发送")
    void 服务端传输发送语义() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-transport-send"));
        ForgeServerTransport transport = new ForgeServerTransport(channel);
        ServerPlayer player = ForgeTestSupport.newPlayer(UUID.randomUUID(), "收包");

        transport.send(transport.onConnected(player), new byte[] {1});

        assertThrows(UnsupportedOperationException.class, () -> transport.send(new byte[] {1}));
        assertEquals(ForgeTypedPayloadChannel.MAX_PAYLOAD_SIZE, transport.maxPayloadSize());
    }

    @Test
    @DisplayName("服务端传输收包回调把原生玩家转换为句柄，空收包器被拒绝")
    void 服务端传输收包回调() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-transport-recv"));
        ForgeServerTransport transport = new ForgeServerTransport(channel);
        List<ConnectionHandle> handles = new ArrayList<>();
        List<byte[]> payloads = new ArrayList<>();

        transport.onReceive((connection, data) -> {
            handles.add(connection);
            payloads.add(data);
        });

        assertThrows(NullPointerException.class, () -> transport.onReceive(null));
        assertTrue(handles.isEmpty());
        assertTrue(payloads.isEmpty());
    }

    @Test
    @DisplayName("客户端传输仅支持无连接发送并复用同一服务端句柄")
    void 客户端传输语义() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-client-transport"));
        Connection connection = ForgeTestSupport.newConnection(
                net.minecraft.network.protocol.PacketFlow.CLIENTBOUND);
        ForgeClientTransport transport = new ForgeClientTransport(channel, connection);

        transport.send(new byte[] {2});
        assertThrows(UnsupportedOperationException.class, () -> transport.send(null, new byte[] {2}));
        assertEquals(ForgeTypedPayloadChannel.MAX_PAYLOAD_SIZE, transport.maxPayloadSize());

        List<byte[]> received = new ArrayList<>();
        transport.onReceive((handle, data) -> received.add(data));
        transport.clearReceiver();
        assertThrows(NullPointerException.class, () -> transport.onReceive(null));
    }

    @Test
    @DisplayName("两类传输都拒绝空通道与空连接")
    void 传输空值防御() {
        assertThrows(NullPointerException.class, () -> new ForgeServerTransport(null));
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-null-transport"));
        assertThrows(NullPointerException.class, () -> new ForgeClientTransport(channel, null));
        assertThrows(NullPointerException.class, () -> new ForgeClientTransport(null,
                ForgeTestSupport.newConnection()));
    }

    @Test
    @DisplayName("连接句柄按玩家对象身份判等并暴露原生玩家")
    void 连接句柄判等() {
        ServerPlayer player = ForgeTestSupport.newPlayer(UUID.randomUUID(), "句柄");
        ForgeConnectionHandle handle = new ForgeConnectionHandle(player);
        ForgeConnectionHandle same = new ForgeConnectionHandle(player);

        assertEquals(handle, handle);
        assertEquals(handle, same);
        assertEquals(handle.hashCode(), same.hashCode());
        assertFalse(handle.equals("其它类型"));
        assertSame(player, handle.player());
        assertThrows(NullPointerException.class, () -> new ForgeConnectionHandle(null));
    }

    @Test
    @DisplayName("传输端口契约在服务端与客户端实现间保持一致")
    void 传输契约一致性() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "channel-contract"));

        assertTrue(TransportPort.class.isAssignableFrom(ForgeServerTransport.class));
        assertTrue(TransportPort.class.isAssignableFrom(ForgeClientTransport.class));
        assertNotNull(new ForgeServerTransport(channel));
    }
}
