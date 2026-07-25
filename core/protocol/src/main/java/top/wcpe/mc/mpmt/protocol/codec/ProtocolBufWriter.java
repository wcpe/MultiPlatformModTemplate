package top.wcpe.mc.mpmt.protocol.codec;

/**
 * 协议写缓冲：定义跨端字节布局的写入原语（与 Minecraft 线缆格式对齐，详见跨栈 spike）。
 *
 * <p>VarInt 为 7 位分组 + 高位续传标志；UTF 为 {@code VarInt(字节数) + UTF-8 字节}；long 为 8 字节大端。
 * 序列化不依赖任何平台类型（ADR-0006），底层收发经 TransportPort（M2 暂不涉及）。
 */
public interface ProtocolBufWriter {

    /** 写入一个字节（取低 8 位）。 */
    ProtocolBufWriter writeByte(int value);

    /** 写入一个 VarInt（Minecraft 标准编码）。 */
    ProtocolBufWriter writeVarInt(int value);

    /** 写入一个定长 int（4 字节大端，用于 CRC32 等定长字段）。 */
    ProtocolBufWriter writeInt(int value);

    /** 写入一个 long（8 字节大端）。 */
    ProtocolBufWriter writeLong(long value);

    /** 写入一个布尔（1 字节）。 */
    ProtocolBufWriter writeBoolean(boolean value);

    /** 写入一个 UTF 字符串（VarInt 长度前缀 + UTF-8 字节）。 */
    ProtocolBufWriter writeUtf(String value);

    /** 写入一段字节（VarInt 长度前缀 + 原始字节）。 */
    ProtocolBufWriter writeBytes(byte[] value);

    /** 导出已写入的全部字节。 */
    byte[] toByteArray();
}
