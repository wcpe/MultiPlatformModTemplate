package top.wcpe.mc.mpmt.platform.fabric.version.v1_21;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricChannel;

/** 1.21.1 单通道 typed payload 类型与双向 codec 注册。 */
final class V1_21PayloadRegistration {

    private final CustomPacketPayload.Type<V1_21Payload> type;

    V1_21PayloadRegistration(FabricChannel channel) {
        ResourceLocation id =
                ResourceLocation.fromNamespaceAndPath(channel.namespace(), channel.path());
        type = new CustomPacketPayload.Type<>(id);
        StreamCodec<RegistryFriendlyByteBuf, V1_21Payload> codec = V1_21Payload.codec(type);
        PayloadTypeRegistry.playC2S().register(type, codec);
        PayloadTypeRegistry.playS2C().register(type, codec);
    }

    CustomPacketPayload.Type<V1_21Payload> type() {
        return type;
    }

    V1_21Payload payload(byte[] data) {
        return new V1_21Payload(type, data);
    }
}
