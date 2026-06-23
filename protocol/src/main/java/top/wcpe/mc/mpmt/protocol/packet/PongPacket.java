package top.wcpe.mc.mpmt.protocol.packet;

import lombok.Value;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/**
 * S2C 心跳 / 往返演示应答：回送 {@link PingPacket} 的 nonce。
 */
@Value
public class PongPacket implements Packet {

    /** 对应 Ping 的 nonce。 */
    long nonce;

    @Override
    public int id() {
        return PacketIds.PONG;
    }

    @Override
    public void encode(ProtocolBufWriter buf) {
        buf.writeLong(nonce);
    }

    /** 与 {@link #encode} 对称的解码。 */
    public static PongPacket decode(ProtocolBufReader buf) {
        return new PongPacket(buf.readLong());
    }
}
