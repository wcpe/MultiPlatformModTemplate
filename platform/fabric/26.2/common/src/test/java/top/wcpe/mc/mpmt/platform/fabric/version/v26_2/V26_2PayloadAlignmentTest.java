package top.wcpe.mc.mpmt.platform.fabric.version.v26_2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** 26.2 类型化载体不增加长度或分帧，逐字节透传 wire-v1。 */
class V26_2PayloadAlignmentTest {

    @Test
    @DisplayName("类型化载体对 PacketCodec 向量逐字节透明")
    void typedCarrier逐字节透明() {
        List<Packet> packets =
                Arrays.asList(
                        new ClientHelloPacket(128, "中文🙂"),
                        new PingPacket(Long.MAX_VALUE),
                        new ServerHudMessagePacket(HudKind.TITLE, "标题", "副标题🙂", 0L),
                        new ServerHudMessagePacket(HudKind.ACTIONBAR, "action", "", -1L),
                        new ServerHudMessagePacket(HudKind.CHAT, "chat", "", Long.MAX_VALUE),
                        new ServerHudMessagePacket(HudKind.TOAST, "toast", "提示", Long.MIN_VALUE));
        PacketCodec packetCodec = new PacketCodec();
        CustomPacketPayload.Type<V26_2Payload> type =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("mpmt", "carrier-test"));
        StreamCodec<RegistryFriendlyByteBuf, V26_2Payload> carrierCodec =
                V26_2Payload.codec(type);

        for (Packet packet : packets) {
            byte[] wire = packetCodec.encode(packet);
            RegistryFriendlyByteBuf buffer =
                    new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            carrierCodec.encode(buffer, new V26_2Payload(type, wire));
            assertArrayEquals(wire, drain(buffer));
            buffer.readerIndex(0);
            assertArrayEquals(wire, carrierCodec.decode(buffer).data());
        }
    }

    private static byte[] drain(RegistryFriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }
}
