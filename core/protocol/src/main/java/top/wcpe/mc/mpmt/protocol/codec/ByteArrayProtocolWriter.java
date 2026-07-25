package top.wcpe.mc.mpmt.protocol.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 基于 {@link ByteArrayOutputStream} 的 {@link ProtocolBufWriter} 默认实现（平台无关，非线程安全，单次编码用）。
 */
public final class ByteArrayProtocolWriter implements ProtocolBufWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @Override
    public ProtocolBufWriter writeByte(int value) {
        out.write(value & 0xFF);
        return this;
    }

    @Override
    public ProtocolBufWriter writeVarInt(int value) {
        // Minecraft 标准 VarInt：7 位一组，高位 0x80 表示后续仍有字节
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
        return this;
    }

    @Override
    public ProtocolBufWriter writeInt(int value) {
        // 4 字节大端
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
        return this;
    }

    @Override
    public ProtocolBufWriter writeLong(long value) {
        // 8 字节大端
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift) & 0xFF);
        }
        return this;
    }

    @Override
    public ProtocolBufWriter writeBoolean(boolean value) {
        out.write(value ? 1 : 0);
        return this;
    }

    @Override
    public ProtocolBufWriter writeUtf(String value) {
        Objects.requireNonNull(value, "value 不能为空");
        byte[] utf = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(utf.length);
        out.write(utf, 0, utf.length);
        return this;
    }

    @Override
    public ProtocolBufWriter writeBytes(byte[] value) {
        Objects.requireNonNull(value, "value 不能为空");
        writeVarInt(value.length);
        out.write(value, 0, value.length);
        return this;
    }

    @Override
    public byte[] toByteArray() {
        return out.toByteArray();
    }
}
