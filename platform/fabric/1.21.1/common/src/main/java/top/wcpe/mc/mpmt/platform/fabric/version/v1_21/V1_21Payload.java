package top.wcpe.mc.mpmt.platform.fabric.version.v1_21;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 1.21.1 类型化自定义载荷，仅透明承载剩余裸字节。 */
public final class V1_21Payload implements CustomPacketPayload {

    private final Type<V1_21Payload> type;
    private final byte[] data;

    public V1_21Payload(Type<V1_21Payload> type, byte[] data) {
        this.type = Objects.requireNonNull(type, "type 不能为空");
        this.data = Objects.requireNonNull(data, "data 不能为空").clone();
    }

    public byte[] data() {
        return data.clone();
    }

    @Override
    public Type<V1_21Payload> type() {
        return type;
    }

    public static StreamCodec<RegistryFriendlyByteBuf, V1_21Payload> codec(
            Type<V1_21Payload> type) {
        return StreamCodec.of(
                (buffer, payload) -> buffer.writeBytes(payload.data),
                buffer -> new V1_21Payload(type, readRemaining(buffer)));
    }

    private static byte[] readRemaining(RegistryFriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }
}
