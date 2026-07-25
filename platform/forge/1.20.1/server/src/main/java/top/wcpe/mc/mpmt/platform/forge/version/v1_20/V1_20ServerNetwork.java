package top.wcpe.mc.mpmt.platform.forge.version.v1_20;

import io.netty.buffer.Unpooled;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeConnectionHandle;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeRawPayloadRouter;
import top.wcpe.mc.mpmt.platform.forge.version.ForgeServerNetwork;

/**
 * Forge 1.20.1 服务端网络适配：裸 CustomPayload + Mixin 路由，复用既有 FML 握手标记注册。
 *
 * <p>不重复注册网络栈；仅抽取通道注册、收发与单包上限。
 */
public final class V1_20ServerNetwork implements ForgeServerNetwork {

    /** 1.20.1 自定义载荷字节上限。 */
    private static final int MAX_PAYLOAD = 1048576;

    private final ResourceLocation channelId;

    public V1_20ServerNetwork(String namespace, String path) {
        this.channelId = new ResourceLocation(namespace, path);
        registerFmlHandshakeMarker();
    }

    private void registerFmlHandshakeMarker() {
        // 仅注册 FML 握手标记通道，实际收发走裸字节 + Mixin（ADR-0018）
        NetworkRegistry.newEventChannel(channelId, () -> "1", version -> true, version -> true);
    }

    @Override
    public ResourceLocation channelId() {
        return channelId;
    }

    @Override
    public void registerReceiver(BiConsumer<ServerPlayer, byte[]> handler) {
        Objects.requireNonNull(handler, "handler 不能为空");
        ForgeRawPayloadRouter.registerServer(channelId, handler);
    }

    @Override
    public void send(ServerPlayer player, byte[] data) {
        FriendlyByteBuf buf =
                new FriendlyByteBuf(Unpooled.buffer(data.length == 0 ? 1 : data.length));
        buf.writeBytes(data);
        player.connection.send(new ClientboundCustomPayloadPacket(channelId, buf));
    }

    @Override
    public ConnectionHandle connectionOf(ServerPlayer player) {
        return new ForgeConnectionHandle(player);
    }

    @Override
    public int maxPayloadSize() {
        return MAX_PAYLOAD;
    }
}
