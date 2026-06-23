package top.wcpe.mc.mpmt.protocol.codec;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import top.wcpe.mc.mpmt.protocol.ProtocolException;

/**
 * 基于 {@code byte[]} 的 {@link ProtocolBufReader} 默认实现（平台无关，非线程安全，单次解码用）。
 *
 * <p>所有读取均做边界检查；越界 / 非法长度抛 {@link ProtocolException}，保证非法 / 截断输入不致崩溃。
 */
public final class ByteArrayProtocolReader implements ProtocolBufReader {

    /** UTF / 字节段长度上限，防止被构造的超大长度触发 OOM（1 MiB 足够覆盖协议用途）。 */
    private static final int MAX_LENGTH = 1 << 20;

    private final byte[] data;
    private int pos;

    public ByteArrayProtocolReader(byte[] data) {
        this.data = Objects.requireNonNull(data, "data 不能为空");
    }

    private void require(int count) {
        if (count < 0 || pos + count > data.length) {
            throw new ProtocolException("字节流截断或越界：需 " + count + " 字节，剩余 " + remaining());
        }
    }

    @Override
    public int readUnsignedByte() {
        require(1);
        return data[pos++] & 0xFF;
    }

    @Override
    public int readVarInt() {
        int result = 0;
        int shift = 0;
        // VarInt 最多 5 字节（32 位）
        for (int i = 0; i < 5; i++) {
            int b = readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new ProtocolException("VarInt 过长（超过 5 字节），字节流非法");
    }

    @Override
    public long readLong() {
        require(8);
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result = (result << 8) | (data[pos++] & 0xFFL);
        }
        return result;
    }

    @Override
    public boolean readBoolean() {
        return readUnsignedByte() != 0;
    }

    @Override
    public String readUtf() {
        return new String(readRaw(), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] readBytes() {
        return readRaw();
    }

    private byte[] readRaw() {
        int length = readVarInt();
        if (length < 0 || length > MAX_LENGTH) {
            throw new ProtocolException("非法的长度前缀：" + length);
        }
        require(length);
        byte[] result = new byte[length];
        System.arraycopy(data, pos, result, 0, length);
        pos += length;
        return result;
    }

    @Override
    public int remaining() {
        return data.length - pos;
    }
}
