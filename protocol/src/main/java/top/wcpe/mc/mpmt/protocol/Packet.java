package top.wcpe.mc.mpmt.protocol;

import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/**
 * 跨端协议包。
 *
 * <p>每个实现声明自己的 {@link #id()} 并实现 {@link #encode}；并约定提供一个
 * {@code static decode(ProtocolBufReader)} 与之对称（由 {@link PacketCodec} 注册分发）。
 * 包是平台无关的纯数据对象，不得携带任何平台原生类型（ADR-0006）。
 */
public interface Packet {

    /** 包 id（写入帧头，用于解码分发）。 */
    int id();

    /** 把包体写入缓冲（不含帧头，帧头由 {@link PacketCodec} 统一写）。 */
    void encode(ProtocolBufWriter buf);
}
