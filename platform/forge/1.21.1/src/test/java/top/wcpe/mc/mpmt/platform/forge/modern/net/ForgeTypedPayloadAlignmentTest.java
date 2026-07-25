package top.wcpe.mc.mpmt.platform.forge.modern.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 37 个 wire-v1 固定向量经过 Forge typed payload 后保持逐字节不变。 */
class ForgeTypedPayloadAlignmentTest {

    private static final Pattern ENCODED =
            Pattern.compile("\\\"encoded\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @Test
    @DisplayName("typed payload 透明承载全部 37 个 wire-v1 向量")
    void 全部golden向量逐字节透明() throws IOException {
        CustomPacketPayload.Type<ForgeTypedPayload> type = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath("mpmt", "carrier-test"));
        StreamCodec<RegistryFriendlyByteBuf, ForgeTypedPayload> codec =
                ForgeTypedPayload.codec(type);
        Matcher matcher = ENCODED.matcher(readGolden());
        int count = 0;
        while (matcher.find()) {
            verify(codec, type, Base64.getDecoder().decode(matcher.group(1)));
            count++;
        }
        assertEquals(37, count, "wire-v1 golden 向量数量必须冻结为 37");
    }

    private static void verify(
            StreamCodec<RegistryFriendlyByteBuf, ForgeTypedPayload> codec,
            CustomPacketPayload.Type<ForgeTypedPayload> type,
            byte[] wire) {
        RegistryFriendlyByteBuf buffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec.encode(buffer, new ForgeTypedPayload(type, wire));
            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), encoded);
            assertArrayEquals(wire, encoded);
            buffer.readerIndex(0);
            assertArrayEquals(wire, codec.decode(buffer).data());
        } finally {
            buffer.release();
        }
    }

    private static String readGolden() throws IOException {
        try (InputStream input = ForgeTypedPayloadAlignmentTest.class
                .getResourceAsStream("/golden/wire-v1.json")) {
            if (input == null) {
                throw new IOException("缺少 wire-v1 golden 资源");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
