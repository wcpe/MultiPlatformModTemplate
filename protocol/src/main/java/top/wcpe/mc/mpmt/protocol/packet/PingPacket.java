package top.wcpe.mc.mpmt.protocol.packet;

import lombok.Value;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/**
 * C2S 心跳 / 往返演示请求：携带一个 nonce，服务端原样回 {@link PongPacket} 以计算 RTT。
 */
@Value
public class PingPacket implements Packet {

    /** 随机 nonce，用于匹配应答与计算 RTT。 */
    long nonce;

    @Override
    public int id() {
        return PacketIds.PING;
    }

    @Override
    public void encode(ProtocolBufWriter buf) {
        buf.writeLong(nonce);
    }

    /** 与 {@link #encode} 对称的解码。 */
    public static PingPacket decode(ProtocolBufReader buf) {
        return new PingPacket(buf.readLong());
    }
}
