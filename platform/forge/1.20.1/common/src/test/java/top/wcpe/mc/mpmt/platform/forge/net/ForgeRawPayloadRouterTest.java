package top.wcpe.mc.mpmt.platform.forge.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.platform.forge.ForgeMinecraftTestSupport;

/** 裸 CustomPayload 静态路由：注册、命中判定与未命中放行的真实行为。 */
class ForgeRawPayloadRouterTest {

    private static ResourceLocation unique(String prefix) {
        return ResourceLocation.tryBuild("mpmt", prefix + "-" + UUID.randomUUID());
    }

    @Test
    @DisplayName("客户端路由：命中则分发并拦下，未命中则放行原版")
    void 客户端命中与放行() {
        ResourceLocation channel = unique("client-route");
        ResourceLocation unknown = unique("client-unknown");
        List<byte[]> received = new ArrayList<>();

        ForgeRawPayloadRouter.registerClient(channel, received::add);

        assertTrue(ForgeRawPayloadRouter.hasClient(channel));
        assertFalse(ForgeRawPayloadRouter.hasClient(unknown));
        assertTrue(ForgeRawPayloadRouter.dispatchClient(channel, new byte[] {1, 2}));
        assertFalse(ForgeRawPayloadRouter.dispatchClient(unknown, new byte[] {3}));
        assertEquals(1, received.size());
        assertArrayEquals(new byte[] {1, 2}, received.get(0));
    }

    @Test
    @DisplayName("服务端路由：把发送方与载荷一并交给处理器")
    void 服务端带发送方分发() {
        ResourceLocation channel = unique("server-route");
        ResourceLocation unknown = unique("server-unknown");
        ServerPlayer sender = ForgeMinecraftTestSupport.newPlayer(UUID.randomUUID(), "发送方");
        List<ServerPlayer> senders = new ArrayList<>();
        List<byte[]> received = new ArrayList<>();

        ForgeRawPayloadRouter.registerServer(channel, (player, data) -> {
            senders.add(player);
            received.add(data);
        });

        assertTrue(ForgeRawPayloadRouter.hasServer(channel));
        assertFalse(ForgeRawPayloadRouter.hasServer(unknown));
        assertTrue(ForgeRawPayloadRouter.dispatchServer(sender, channel, new byte[] {7}));
        assertFalse(ForgeRawPayloadRouter.dispatchServer(sender, unknown, new byte[] {8}));
        assertEquals(1, senders.size());
        assertSame(sender, senders.get(0));
        assertArrayEquals(new byte[] {7}, received.get(0));
    }

    @Test
    @DisplayName("行为可被后注册者覆盖，且读出的字节与原缓冲解耦")
    void 覆盖注册与字节解耦() {
        ResourceLocation channel = unique("override");
        List<String> calls = new ArrayList<>();
        ForgeRawPayloadRouter.registerClient(channel, data -> calls.add("首个"));
        ForgeRawPayloadRouter.registerClient(channel, data -> calls.add("后注册"));

        ForgeRawPayloadRouter.dispatchClient(channel, new byte[] {0});
        assertEquals(List.of("后注册"), calls);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeBytes(new byte[] {4, 5, 6});
            byte[] read = ForgeRawPayloadRouter.readAll(buffer);
            assertArrayEquals(new byte[] {4, 5, 6}, read);
            assertEquals(0, buffer.readableBytes());
            assertFalse(ForgeRawPayloadRouter.dispatchClient(unique("unregistered"), read));
        } finally {
            buffer.release();
        }
    }

    @Test
    @DisplayName("连接句柄仅按玩家 UUID 判等与哈希")
    void 句柄按UUID判等() {
        UUID playerId = UUID.randomUUID();
        ForgeConnectionHandle left =
                new ForgeConnectionHandle(ForgeMinecraftTestSupport.newPlayer(playerId, "甲"));
        ForgeConnectionHandle right =
                new ForgeConnectionHandle(ForgeMinecraftTestSupport.newPlayer(playerId, "乙"));
        ForgeConnectionHandle other =
                new ForgeConnectionHandle(ForgeMinecraftTestSupport.newPlayer(UUID.randomUUID(), "丙"));

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertFalse(left.equals(other));
        assertFalse(left.equals(null));
        assertFalse(left.equals("不是句柄"));
        assertEquals(left, left);
        assertSame(left.player(), left.player());
    }

    @Test
    @DisplayName("无客户端处理器的通道不会产生分发副作用")
    void 无处理器通道放行() {
        List<ConnectionHandle> touched = new ArrayList<>();
        assertFalse(ForgeRawPayloadRouter.dispatchClient(unique("empty"), new byte[0]));
        assertEquals(0, touched.size());
    }
}
