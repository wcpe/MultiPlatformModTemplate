package top.wcpe.mc.mpmt.platform.fabric.version.v26_2;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricChannel;

/** 26.2 单通道 typed payload 类型与双向 codec 注册。 */
final class V26_2PayloadRegistration {

    private final CustomPacketPayload.Type<V26_2Payload> type;

    V26_2PayloadRegistration(FabricChannel channel) {
        Identifier id = Identifier.fromNamespaceAndPath(channel.namespace(), channel.path());
        type = new CustomPacketPayload.Type<>(id);
        StreamCodec<RegistryFriendlyByteBuf, V26_2Payload> codec = V26_2Payload.codec(type);
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
    }

    CustomPacketPayload.Type<V26_2Payload> type() {
        return type;
    }

    V26_2Payload payload(byte[] data) {
        return new V26_2Payload(type, data);
    }
}
