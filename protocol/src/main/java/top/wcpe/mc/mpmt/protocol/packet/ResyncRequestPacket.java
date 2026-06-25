package top.wcpe.mc.mpmt.protocol.packet;

import lombok.Value;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/**
 * C2S 重同步请求（FR-24）：连接重建 / 心跳超时后，客户端发本包请求服务端重发自 {@link #sinceRevision} 起的权威状态。
 *
 * <p>修订号为单调递增 long（应用域定义其语义），编码为 8 字节大端。重发哪些状态由服务端 {@code ResyncCoordinator}
 * 的处理器据修订号决定。
 */
@Value
public class ResyncRequestPacket implements Packet {

    /** 客户端已有的最新修订号；服务端据此重发自该修订起的权威状态。 */
    long sinceRevision;

    @Override
    public int id() {
        return PacketIds.RESYNC_REQUEST;
    }

    @Override
    public void encode(ProtocolBufWriter buf) {
        buf.writeLong(sinceRevision);
    }

    /** 与 {@link #encode} 对称的解码。 */
    public static ResyncRequestPacket decode(ProtocolBufReader buf) {
        return new ResyncRequestPacket(buf.readLong());
    }
}
