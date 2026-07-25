package top.wcpe.mc.mpmt.protocol.packet;

import lombok.Value;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/**
 * 分片包（可靠性层）：大于平台 / 版本上限的载荷被切成多片传输，按 {@code seqId} 归组、按 {@code index} 排序重组。
 *
 * <p>{@code crc32} 为<b>完整原载荷</b>的 CRC32（各片冗余携带，重组后校验）。布局：
 * {@code seqId:varint, index:varint, total:varint, crc32:int, payload:bytes}（network spec §3.2）。
 */
@Value
public class FragmentPacket implements Packet {

    /** 分片序列号（同一原载荷的各片共享）。 */
    int seqId;

    /** 本片序号（0 起）。 */
    int index;

    /** 总片数。 */
    int total;

    /** 完整原载荷的 CRC32。 */
    int crc32;

    /** 本片字节。 */
    byte[] payload;

    @Override
    public int id() {
        return PacketIds.FRAGMENT;
    }

    @Override
    public void encode(ProtocolBufWriter buf) {
        buf.writeVarInt(seqId);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeInt(crc32);
        buf.writeBytes(payload);
    }

    /** 与 {@link #encode} 对称的解码。 */
    public static FragmentPacket decode(ProtocolBufReader buf) {
        int seqId = buf.readVarInt();
        int index = buf.readVarInt();
        int total = buf.readVarInt();
        int crc32 = buf.readInt();
        byte[] payload = buf.readBytes();
        return new FragmentPacket(seqId, index, total, crc32, payload);
    }
}
