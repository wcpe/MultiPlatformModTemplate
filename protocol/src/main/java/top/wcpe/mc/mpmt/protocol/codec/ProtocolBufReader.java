package top.wcpe.mc.mpmt.protocol.codec;

/**
 * 协议读缓冲：定义跨端字节布局的读取原语，与 {@link ProtocolBufWriter} 一一对应。
 *
 * <p>遇到非法 / 截断字节流时抛 {@link top.wcpe.mc.mpmt.protocol.ProtocolException}，明确拒绝、不崩溃。
 */
public interface ProtocolBufReader {

    /** 读取一个无符号字节（0–255）。 */
    int readUnsignedByte();

    /** 读取一个 VarInt（Minecraft 标准编码）。 */
    int readVarInt();

    /** 读取一个定长 int（4 字节大端）。 */
    int readInt();

    /** 读取一个 long（8 字节大端）。 */
    long readLong();

    /** 读取一个布尔。 */
    boolean readBoolean();

    /** 读取一个 UTF 字符串。 */
    String readUtf();

    /** 读取一段字节。 */
    byte[] readBytes();

    /** 剩余未读字节数。 */
    int remaining();
}
