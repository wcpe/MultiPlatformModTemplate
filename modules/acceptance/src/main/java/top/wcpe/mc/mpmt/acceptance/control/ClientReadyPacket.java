package top.wcpe.mc.mpmt.acceptance.control;

import lombok.Value;

/** C2S：程序化客户端连入后上报控制协议版本及实际 Java 运行身份。 */
@Value
public class ClientReadyPacket implements AcceptanceControlPacket {

    /** 客户端控制协议版本。 */
    int protocolVersion;

    /** 客户端实际 Java 主版本。 */
    int javaMajor;

    /** 客户端实际 Java 可执行文件。 */
    String javaExecutable;

    public ClientReadyPacket(int protocolVersion, int javaMajor, String javaExecutable) {
        this.protocolVersion = protocolVersion;
        this.javaMajor = javaMajor;
        this.javaExecutable = javaExecutable == null ? "" : javaExecutable;
    }

    /**
     * 旧平台源码兼容构造；缺失 Java 身份时服务端应拒绝作为 v2 正式就绪。
     *
     * @deprecated 请使用携带 javaMajor / javaExecutable 的三参构造
     */
    @Deprecated
    public ClientReadyPacket(int protocolVersion) {
        this(protocolVersion, 0, "");
    }
}
