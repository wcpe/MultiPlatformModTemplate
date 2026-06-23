package top.wcpe.mc.mpmt.protocol.packet;

import lombok.Value;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/**
 * C2S 客户端握手问候：上报客户端协议版本与 mod 版本，供服务端做版本协商（ADR-0006）。
 */
@Value
public class ClientHelloPacket implements Packet {

    /** 客户端协议版本，服务端据此协商兼容性。 */
    int protocolVersion;

    /** 客户端 mod 版本（信息性）。 */
    String modVersion;

    @Override
    public int id() {
        return PacketIds.CLIENT_HELLO;
    }

    @Override
    public void encode(ProtocolBufWriter buf) {
        buf.writeVarInt(protocolVersion);
        buf.writeUtf(modVersion);
    }

    /** 与 {@link #encode} 对称的解码。 */
    public static ClientHelloPacket decode(ProtocolBufReader buf) {
        int protocolVersion = buf.readVarInt();
        String modVersion = buf.readUtf();
        return new ClientHelloPacket(protocolVersion, modVersion);
    }
}
