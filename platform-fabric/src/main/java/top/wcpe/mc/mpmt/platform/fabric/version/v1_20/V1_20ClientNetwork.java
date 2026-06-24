package top.wcpe.mc.mpmt.platform.fabric.version.v1_20;

import java.util.Objects;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricClientNetwork;

/**
 * 1.20.1 客户端网络绑定（L4 vX_Y，仅客户端环境）：用 Fabric {@code ClientPlayNetworking}
 * （ResourceLocation 通道 + FriendlyByteBuf）实现 {@link FabricClientNetwork}。
 *
 * <p>{@link Environment}{@code (CLIENT)} 标注——仅客户端加载，杜绝在专用服上加载客户端类。
 * 仅本类引用 1.20.1 客户端网络 API；版本升级 = 另写 vX_Y，不改上层（ADR-0003）。
 */
@Environment(EnvType.CLIENT)
public final class V1_20ClientNetwork implements FabricClientNetwork {

    /** 1.20.1 自定义载荷字节上限（vanilla custom payload 上限 2^20）。 */
    private static final int MAX_PAYLOAD = 1048576;

    private final ResourceLocation channel;

    public V1_20ClientNetwork(String namespace, String path) {
        this.channel = new ResourceLocation(namespace, path);
    }

    @Override
    public void registerReceiver(Consumer<byte[]> handler) {
        Objects.requireNonNull(handler, "handler 不能为空");
        ClientPlayNetworking.registerGlobalReceiver(
                channel,
                (client, networkHandler, buf, responseSender) -> {
                    // 在网络线程立即读出字节（buf 随后释放），交给上层（线程安全的 PacketDispatcher）
                    byte[] data = readAll(buf);
                    handler.accept(data);
                });
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
