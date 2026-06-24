package top.wcpe.mc.mpmt.protocol.packet;

import lombok.Value;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/** C2S 客户端标识上报：握手接受后上报弱客户端标识（hex 字符串）。 */
@Value
public class ClientIdReportPacket implements Packet {

    /** 弱客户端标识（SHA-256 hex 等）。 */
    String clientId;

    @Override
    public int id() {
        return PacketIds.CLIENT_ID_REPORT;
    }

    @Override
    public void encode(ProtocolBufWriter buf) {
        buf.writeUtf(clientId);
    }

    /** 与 {@link #encode} 对称的解码。 */
    public static ClientIdReportPacket decode(ProtocolBufReader buf) {
        return new ClientIdReportPacket(buf.readUtf());
    }
}
