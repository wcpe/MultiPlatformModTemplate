package top.wcpe.mc.mpmt.platform.fabric.version.v1_20;

import java.util.Objects;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricChannel;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricClientNetwork;

/** Fabric 1.20.1 客户端裸字节网络适配器。 */
@Environment(EnvType.CLIENT)
public final class V1_20ClientNetwork implements FabricClientNetwork {

    private static final int MAX_PAYLOAD = 1048576;

    private final ResourceLocation channel;
    private volatile Consumer<byte[]> receiver;

    public V1_20ClientNetwork(FabricChannel channel) {
        this.channel = new ResourceLocation(channel.namespace(), channel.path());
        ClientPlayNetworking.registerGlobalReceiver(
                this.channel,
                (client, networkHandler, buf, responseSender) -> {
                    Consumer<byte[]> current = receiver;
                    if (current != null) {
                        current.accept(readAll(buf));
                    }
                });
    }

    @Override
    public void registerReceiver(Consumer<byte[]> handler) {
        receiver = Objects.requireNonNull(handler, "handler 不能为空");
    }

    @Override
    public void clearReceiver() {
        receiver = null;
    }

    @Override
    public void send(byte[] data) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBytes(data);
        ClientPlayNetworking.send(channel, buf);
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
