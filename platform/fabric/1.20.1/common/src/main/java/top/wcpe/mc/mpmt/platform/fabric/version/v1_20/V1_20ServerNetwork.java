package top.wcpe.mc.mpmt.platform.fabric.version.v1_20;

import java.util.Objects;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricConnectionHandle;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricChannel;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricServerNetwork;

/** Fabric 1.20.1 服务端裸字节网络适配器。 */
public final class V1_20ServerNetwork implements FabricServerNetwork {

    private static final int MAX_PAYLOAD = 1048576;

    private final ResourceLocation channel;

    public V1_20ServerNetwork(FabricChannel channel) {
        this.channel = new ResourceLocation(channel.namespace(), channel.path());
    }

    @Override
    public void registerReceiver(BiConsumer<ConnectionHandle, byte[]> handler) {
        Objects.requireNonNull(handler, "handler 不能为空");
        ServerPlayNetworking.registerGlobalReceiver(
                channel,
                (server, player, networkHandler, buf, responseSender) ->
                        handler.accept(new FabricConnectionHandle(player), readAll(buf)));
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        ServerPlayer player = ((FabricConnectionHandle) connection).player();
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBytes(data);
        ServerPlayNetworking.send(player, channel, buf);
    }

    @Override
    public int maxPayloadSize() {
        return MAX_PAYLOAD;
    }

    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }
}
