package top.wcpe.mc.mpmt.platform.forge.net;

import io.netty.buffer.Unpooled;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/** Forge 客户端裸载荷传输：与服务端产品通道共用同一 PacketDispatcher 收发管线。 */
@OnlyIn(Dist.CLIENT)
public final class ForgeClientTransport implements TransportPort {

    private static final int MAX_PAYLOAD = 1048576;
    private static final ConnectionHandle SERVER = new ConnectionHandle() { };

    private final ResourceLocation channel;

    public ForgeClientTransport(ResourceLocation channel) {
        this.channel = Objects.requireNonNull(channel, "channel 不能为空");
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        throw new UnsupportedOperationException("客户端传输只支持无连接发送");
    }

    @Override
    public void send(byte[] data) {
        if (Minecraft.getInstance().getConnection() == null) {
            throw new IllegalStateException("客户端尚未连接服务器");
        }
        FriendlyByteBuf buffer =
                new FriendlyByteBuf(Unpooled.buffer(data.length == 0 ? 1 : data.length));
        int readableBytes = buffer.writeBytes(data).readableBytes();
        if (readableBytes != data.length) {
            throw new IllegalStateException("客户端载荷写入不完整");
        }
        Minecraft.getInstance()
                .getConnection()
                .send(new ServerboundCustomPayloadPacket(channel, buffer));
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        Objects.requireNonNull(handler, "handler 不能为空");
        ForgeRawPayloadRouter.registerClient(
                channel, data -> handler.accept(SERVER, data));
    }

    @Override
    public int maxPayloadSize() {
        return MAX_PAYLOAD;
    }

    /** 客户端唯一服务端连接句柄，供断线时清理协议可靠性状态。 */
    public ConnectionHandle serverConnection() {
        return SERVER;
    }
}
