package top.wcpe.mc.mpmt.protocol.packet;

import lombok.Value;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/** 双向分片重发请求：接收端首次重组超时后，请求发送端按序列号重发完整分片组。 */
@Value
public class FragmentRetryRequestPacket implements Packet {

    /** 请求重发的分片序列号。 */
    int seqId;

    @Override
    public int id() {
        return PacketIds.FRAGMENT_RETRY_REQUEST;
    }

    @Override
    public void encode(ProtocolBufWriter buf) {
        buf.writeVarInt(seqId);
    }

    /** 与 {@link #encode} 对称的解码。 */
    public static FragmentRetryRequestPacket decode(ProtocolBufReader buf) {
        return new FragmentRetryRequestPacket(buf.readVarInt());
    }
}
