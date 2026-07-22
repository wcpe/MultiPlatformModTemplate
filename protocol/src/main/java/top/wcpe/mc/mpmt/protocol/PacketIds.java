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

    /** S2C 服务端消息（欢迎 / 提示 / 封禁告知等）。 */
    public static final int SERVER_MESSAGE = 0x02;

    /** S2C 断开通知（携带原因）。 */
    public static final int DISCONNECT = 0x03;

    /** S2C 跨端 HUD 消息（title / actionbar / toast / chat）。 */
    public static final int SERVER_HUD_MESSAGE = 0x05;

    /** C2S 客户端标识上报（弱标识）。 */
    public static final int CLIENT_ID_REPORT = 0x81;

    /** C2S 重同步请求（可靠性层：连接重建 / 心跳超时后请求重发自某修订起的权威状态）。 */
    public static final int RESYNC_REQUEST = 0x82;

    /** S2C 服务端心跳探测。 */
    public static final int PING = 0x04;

    /** C2S 客户端心跳应答。 */
    public static final int PONG = 0x83;

    /** S2C 服务端要求客户端发起重同步。 */
    public static final int RESYNC_REQUIRED = 0x06;

    /** 双向 分片包（可靠性层：大包切片 + 重组）。 */
    public static final int FRAGMENT = 0x10;

    /** 双向 分片重发请求（可靠性层：首次重组超时后请求完整重发）。 */
    public static final int FRAGMENT_RETRY_REQUEST = 0x11;

    private PacketIds() {
        // 常量类不实例化
    }
}
