package top.wcpe.mc.mpmt.protocol.packet;

import lombok.Value;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/** S2C 重同步要求：服务端通知客户端从指定权威修订发起重同步请求。 */
@Value
public class ResyncRequiredPacket implements Packet {

    /** 服务端要求客户端据此发起重同步的权威修订号。 */
    long authoritativeRevision;

    @Override
    public int id() {
        return PacketIds.RESYNC_REQUIRED;
    }

    @Override
    public void encode(ProtocolBufWriter buf) {
        buf.writeLong(authoritativeRevision);
    }

    /** 与 {@link #encode} 对称的解码。 */
    public static ResyncRequiredPacket decode(ProtocolBufReader buf) {
        return new ResyncRequiredPacket(buf.readLong());
    }
}
