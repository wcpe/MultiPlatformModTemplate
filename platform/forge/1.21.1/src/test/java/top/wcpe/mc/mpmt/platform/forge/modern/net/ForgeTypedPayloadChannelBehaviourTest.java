package top.wcpe.mc.mpmt.platform.forge.modern.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.forge.modern.Forge121MinecraftTestSupport;

/**
 * 类型化载荷通道：类型标识、收发器注册与入站分发。
 *
 * <p>真实通道构建依赖 ModLauncher 变换（纯 JVM 不可达），故经 {@code Unsafe} 造实例并只注入载荷类型，
 * 聚焦"对上层只暴露裸字节"的收包与注册语义。
 */
class ForgeTypedPayloadChannelBehaviourTest {

    private static ForgeTypedPayloadChannel channelWithType(ResourceLocation channelId) {
        return Forge121MinecraftTestSupport.newTypedChannel(channelId);
    }

    @Test
    @DisplayName("单包上限常量固定为 1 MiB")
    void 单包上限常量() {
        assertEquals(1_048_576, ForgeTypedPayloadChannel.MAX_PAYLOAD_SIZE);
    }

    @Test
    @DisplayName("注册收发器拒绝空值，清除客户端收发器幂等")
    void 注册拒绝空值() {
        ForgeTypedPayloadChannel channel = channelWithType(
                ResourceLocation.fromNamespaceAndPath("mpmt", "test-empties"));

        assertThrows(NullPointerException.class, () -> channel.registerServerReceiver(null));
        assertThrows(NullPointerException.class, () -> channel.registerClientReceiver(null));

        channel.clearClientReceiver();
        channel.clearClientReceiver();
    }

    @Test
    @DisplayName("客户端入站把裸字节交给客户端收发器")
    void 客户端入站分发() {
        ForgeTypedPayloadChannel channel = channelWithType(
                ResourceLocation.fromNamespaceAndPath("mpmt", "test-client-in"));
        List<byte[]> received = new ArrayList<>();
        channel.registerClientReceiver(received::add);

        deliver(channel, payload(channel, new byte[] {7, 8}), clientContext());

        assertEquals(1, received.size());
        assertArrayEquals(new byte[] {7, 8}, received.get(0));
    }

    @Test
    @DisplayName("客户端收发器为空时入站静默丢弃，不抛异常")
    void 客户端收发器为空时静默() {
        ForgeTypedPayloadChannel channel = channelWithType(
                ResourceLocation.fromNamespaceAndPath("mpmt", "test-client-empty"));

        deliver(channel, payload(channel, new byte[] {1}), clientContext());
    }

    @Test
    @DisplayName("清除客户端收发器后入站不再分发")
    void 清除后不再分发() {
        ForgeTypedPayloadChannel channel = channelWithType(
                ResourceLocation.fromNamespaceAndPath("mpmt", "test-client-clear"));
        List<byte[]> received = new ArrayList<>();
        channel.registerClientReceiver(received::add);
        channel.clearClientReceiver();

        deliver(channel, payload(channel, new byte[] {2}), clientContext());

        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("服务端入站把发送方与裸字节一并交给服务端收发器")
    void 服务端入站分发() {
        ForgeTypedPayloadChannel channel = channelWithType(
                ResourceLocation.fromNamespaceAndPath("mpmt", "test-server-in"));
        ServerPlayer sender = Forge121MinecraftTestSupport.newPlayer(java.util.UUID.randomUUID(), "发送方");
        List<ServerPlayer> senders = new ArrayList<>();
        List<byte[]> received = new ArrayList<>();
        channel.registerServerReceiver((player, data) -> {
            senders.add(player);
            received.add(data);
        });

        deliver(channel, payload(channel, new byte[] {9}), serverContext(sender));

        assertEquals(1, senders.size());
        assertSame(sender, senders.get(0));
        assertArrayEquals(new byte[] {9}, received.get(0));
    }

    @Test
    @DisplayName("服务端收发器为空时入站静默丢弃")
    void 服务端收发器为空时静默() {
        ForgeTypedPayloadChannel channel = channelWithType(
                ResourceLocation.fromNamespaceAndPath("mpmt", "test-server-empty"));
        ServerPlayer sender = Forge121MinecraftTestSupport.newPlayer(java.util.UUID.randomUUID(), "发送方");

        deliver(channel, payload(channel, new byte[] {1}), serverContext(sender));
    }

    @Test
    @DisplayName("载荷对象保真承载类型与裸字节")
    void 载荷对象保真() {
        CustomPacketPayload.Type<ForgeTypedPayload> type = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath("mpmt", "test-payload"));
        byte[] data = {3, 1, 4};

        ForgeTypedPayload payload = new ForgeTypedPayload(type, data);

        assertSame(type, payload.type());
        assertArrayEquals(data, payload.data());
        assertNotNull(payload.toString());
    }

    @Test
    @DisplayName("发送路径在无底层通道时明确失败而非静默丢弃")
    void 发送路径明确失败() {
        ForgeTypedPayloadChannel channel = channelWithType(
                ResourceLocation.fromNamespaceAndPath("mpmt", "test-send-fail"));

        assertThrows(NullPointerException.class, () -> channel.sendToPlayer((ServerPlayer) null, new byte[] {1}));
        assertThrows(NullPointerException.class, () -> channel.sendToServer(null, new byte[] {1}));
    }

    private static ForgeTypedPayload payload(ForgeTypedPayloadChannel channel, byte[] data) {
        CustomPacketPayload.Type<ForgeTypedPayload> type = payloadTypeOf(channel);
        return new ForgeTypedPayload(type, data);
    }

    @SuppressWarnings("unchecked")
    private static CustomPacketPayload.Type<ForgeTypedPayload> payloadTypeOf(ForgeTypedPayloadChannel channel) {
        return (CustomPacketPayload.Type<ForgeTypedPayload>) Forge121MinecraftTestSupport.getField(
                channel, Forge121MinecraftTestSupport.field(channel.getClass(), "type"));
    }

    /** 直接调用包私有 {@code receive}：入站分发的唯一入口。 */
    private static void deliver(ForgeTypedPayloadChannel channel, ForgeTypedPayload payload,
            net.minecraftforge.event.network.CustomPayloadEvent.Context context) {
        try {
            java.lang.reflect.Method method = ForgeTypedPayloadChannel.class.getDeclaredMethod(
                    "receive", ForgeTypedPayload.class,
                    net.minecraftforge.event.network.CustomPayloadEvent.Context.class);
            method.setAccessible(true);
            method.invoke(channel, payload, context);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法投递入站载荷", error);
        }
    }

    private static net.minecraftforge.event.network.CustomPayloadEvent.Context clientContext() {
        net.minecraft.network.Connection connection = Forge121MinecraftTestSupport.newClientConnection();
        net.minecraftforge.event.network.CustomPayloadEvent.Context context =
                new net.minecraftforge.event.network.CustomPayloadEvent.Context(connection);
        Forge121MinecraftTestSupport.set(context, "client", Boolean.TRUE);
        return context;
    }

    /** 服务端上下文：底层连接挂一个带 {@code player} 字段的包监听器，使 {@code getSender()} 可解析。 */
    private static net.minecraftforge.event.network.CustomPayloadEvent.Context serverContext(ServerPlayer sender) {
        net.minecraft.network.Connection connection = Forge121MinecraftTestSupport.newClientConnection();
        net.minecraft.server.network.ServerGamePacketListenerImpl listener =
                Forge121MinecraftTestSupport.allocateInstance(
                        net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        Forge121MinecraftTestSupport.set(listener, "player", sender);
        Forge121MinecraftTestSupport.set(connection, "packetListener", listener);
        return new net.minecraftforge.event.network.CustomPayloadEvent.Context(connection);
    }
}
