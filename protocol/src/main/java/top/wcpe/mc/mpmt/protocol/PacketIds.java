package top.wcpe.mc.mpmt.protocol;

/**
 * 协议包 id 常量（单一真源，取值约定见 network spec §3.2）。
 *
 * <p>约定：S2C 用低位、C2S 用 0x80 起的高位，便于人工辨别方向。M2 仅落地握手与往返演示所需的 4 个包，
 * 其余（分片 / 重同步 / 标识上报 / 封禁等）随网络特性增量添加。
 */
public final class PacketIds {

    /** C2S 客户端握手问候。 */
    public static final int CLIENT_HELLO = 0x80;

    /** S2C 服务端握手应答。 */
    public static final int SERVER_HELLO = 0x01;

    /** C2S 心跳 / 往返演示请求。 */
    public static final int PING = 0x83;

    /** S2C 心跳 / 往返演示应答。 */
    public static final int PONG = 0x04;

    private PacketIds() {
        // 常量类不实例化
    }
}
