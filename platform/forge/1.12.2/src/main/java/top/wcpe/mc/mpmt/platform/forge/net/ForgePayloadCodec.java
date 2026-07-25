package top.wcpe.mc.mpmt.platform.forge.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

/** 1.12.2 自定义负载外层：只包装通道名，内部 payload 原样透传。 */
public final class ForgePayloadCodec {

    private ForgePayloadCodec() {
    }

    public static FMLProxyPacket outgoing(String channel, byte[] data) {
        Objects.requireNonNull(channel, "channel 不能为空");
        Objects.requireNonNull(data, "data 不能为空");
        PacketBuffer payload =
                new PacketBuffer(Unpooled.wrappedBuffer(Arrays.copyOf(data, data.length)));
        return new FMLProxyPacket(payload, channel);
    }

    public static byte[] incoming(FMLProxyPacket packet) {
        Objects.requireNonNull(packet, "packet 不能为空");
        ByteBuf payload = packet.payload();
        byte[] data = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), data);
        return data;
    }
}
