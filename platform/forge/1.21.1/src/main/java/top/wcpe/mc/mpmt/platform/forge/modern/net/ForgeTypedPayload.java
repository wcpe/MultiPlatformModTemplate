package top.wcpe.mc.mpmt.platform.forge.modern.net;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Forge 1.21.1 类型化自定义载荷，仅透明承载产品或验收协议裸字节。 */
public final class ForgeTypedPayload implements CustomPacketPayload {

    private final Type<ForgeTypedPayload> type;
    private final byte[] data;

    public ForgeTypedPayload(Type<ForgeTypedPayload> type, byte[] data) {
        this.type = Objects.requireNonNull(type, "载荷类型不能为空");
        this.data = Objects.requireNonNull(data, "载荷数据不能为空").clone();
    }

    public byte[] data() {
        return data.clone();
    }

    @Override
    public Type<ForgeTypedPayload> type() {
        return type;
    }

    public static StreamCodec<RegistryFriendlyByteBuf, ForgeTypedPayload> codec(
            Type<ForgeTypedPayload> type) {
        return StreamCodec.of(
                (buffer, payload) -> buffer.writeBytes(payload.data),
                buffer -> new ForgeTypedPayload(type, readRemaining(buffer)));
    }

    private static byte[] readRemaining(RegistryFriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }
}
