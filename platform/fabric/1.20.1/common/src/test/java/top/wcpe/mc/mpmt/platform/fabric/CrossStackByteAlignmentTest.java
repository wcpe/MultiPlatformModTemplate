package top.wcpe.mc.mpmt.platform.fabric;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.UncheckedIOException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 跨栈字节对齐 spike（ADR-0006 / network spec §3.4）。
 *
 * <p>证明同一逻辑包：经 Fabric 客户端的 {@link FriendlyByteBuf} 写出的字节，
 * 与 Paper 服务端 {@code PluginMessageListener} 收到的 {@code byte[]}（此处以普通
 * {@link DataOutputStream} 路径独立实现）<b>逐字节一致</b>。
 *
 * <p>这为后续 protocol 单一真源（M2）扫清"两栈字节对不上"的风险，且无需启动真实 MC，
 * 可纳入纯 JVM 自动化测试（经用户确认：本 spike 用自动化字节等价校验，实机互通留到 realserver 验收）。
 */
class CrossStackByteAlignmentTest {

    @Test
    @DisplayName("典型 spike 包：u8+u8+varint+utf 两栈字节完全一致")
    void 典型包字节一致() {
        int protocolVersion = 1;
        int packetId = 0x80;
        int payloadValue = 300;
        String text = "你好-mpmt-hello";

        byte[] fabricBytes = encodeViaFabric(protocolVersion, packetId, payloadValue, text);
        byte[] paperBytes = encodeViaPlain(protocolVersion, packetId, payloadValue, text);

        assertArrayEquals(paperBytes, fabricBytes);
    }

    @ParameterizedTest
    @DisplayName("VarInt 在边界取值上两栈编码一致")
    @ValueSource(ints = {0, 1, 127, 128, 255, 256, 16383, 16384, 2097151, 2097152, Integer.MAX_VALUE})
    void varint边界一致(int value) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(value);
        byte[] fabricBytes = drain(buf);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            writeVarInt(out, value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertArrayEquals(bos.toByteArray(), fabricBytes);
    }

    /** Fabric 栈：经 {@link FriendlyByteBuf} 写出 spike 包。 */
    private static byte[] encodeViaFabric(int protocolVersion, int packetId, int payloadValue, String text) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(protocolVersion);
        buf.writeByte(packetId);
        buf.writeVarInt(payloadValue);
        buf.writeUtf(text);
        return drain(buf);
    }

    /** Paper 栈：以普通 {@code byte[]} 路径独立实现同一字节布局（不依赖任何 MC 类型）。 */
    private static byte[] encodeViaPlain(int protocolVersion, int packetId, int payloadValue, String text) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(protocolVersion);
            out.writeByte(packetId);
            writeVarInt(out, payloadValue);
            byte[] utf = text.getBytes(StandardCharsets.UTF_8);
            writeVarInt(out, utf.length);
            out.write(utf);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    /** Minecraft 标准 VarInt 编码（7 位分组 + 高位续传标志），与 FriendlyByteBuf.writeVarInt 等价。 */
    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static byte[] drain(FriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }
}
