package top.wcpe.mc.mpmt.protocol.packet;

import lombok.Value;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/** S2C 断开通知：携带断开原因（如被封禁）。实际踢出连接由平台 L3 执行。 */
@Value
public class DisconnectPacket implements Packet {

    /** 断开原因。 */
    String reason;

    @Override
    public int id() {
        return PacketIds.DISCONNECT;
    }

    @Override
    public void encode(ProtocolBufWriter buf) {
        buf.writeUtf(reason);
    }

    /** 与 {@link #encode} 对称的解码。 */
    public static DisconnectPacket decode(ProtocolBufReader buf) {
        return new DisconnectPacket(buf.readUtf());
    }
}
