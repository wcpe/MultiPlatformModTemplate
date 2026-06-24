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
import top.wcpe.mc.mpmt.platform.fabric.version.FabricServerNetwork;

/**
 * 1.20.1 服务端网络绑定（L4 vX_Y）：用 Fabric {@code ServerPlayNetworking}
 * （ResourceLocation 通道 + FriendlyByteBuf）实现 {@link FabricServerNetwork}。
 *
 * <p>仅本类引用 1.20.1 网络 API；版本升级（如 1.20.5 codec 化）= 另写一个 vX_Y 绑定，不改本类与上层（ADR-0003）。
 */
public final class V1_20ServerNetwork implements FabricServerNetwork {

    /** 1.20.1 自定义载荷字节上限（vanilla custom payload 上限 2^20）。 */
    private static final int MAX_PAYLOAD = 1048576;

    private final ResourceLocation channel;

    public V1_20ServerNetwork(String namespace, String path) {
        this.channel = new ResourceLocation(namespace, path);
    }

    @Override
    public void registerReceiver(BiConsumer<ConnectionHandle, byte[]> handler) {
        Objects.requireNonNull(handler, "handler 不能为空");
        ServerPlayNetworking.registerGlobalReceiver(
                channel,
                (server, player, networkHandler, buf, responseSender) -> {
                    // 在网络线程立即读出字节（buf 随后释放），交给上层（线程安全的 PacketDispatcher）
                    byte[] data = readAll(buf);
                    handler.accept(new FabricConnectionHandle(player), data);
                });
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
