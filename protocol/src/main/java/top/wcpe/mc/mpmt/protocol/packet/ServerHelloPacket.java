package top.wcpe.mc.mpmt.protocol.packet;

import lombok.Value;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/**
 * S2C 服务端握手应答：回送服务端协议版本、会话 id 与是否接受（版本协商结果）。
 */
@Value
public class ServerHelloPacket implements Packet {

    /** 服务端协议版本。 */
    int protocolVersion;

    /** 服务端分配的会话 id。 */
    String sessionId;

    /** 是否接受本次握手（版本不兼容时为 false）。 */
    boolean accepted;

    @Override
    public int id() {
        return PacketIds.SERVER_HELLO;
    }

    @Override
    public void encode(ProtocolBufWriter buf) {
        buf.writeVarInt(protocolVersion);
        buf.writeUtf(sessionId);
        buf.writeBoolean(accepted);
    }

    /** 与 {@link #encode} 对称的解码。 */
    public static ServerHelloPacket decode(ProtocolBufReader buf) {
        int protocolVersion = buf.readVarInt();
        String sessionId = buf.readUtf();
        boolean accepted = buf.readBoolean();
        return new ServerHelloPacket(protocolVersion, sessionId, accepted);
    }
}
