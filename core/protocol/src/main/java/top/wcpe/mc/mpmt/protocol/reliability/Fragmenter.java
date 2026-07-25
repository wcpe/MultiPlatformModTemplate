package top.wcpe.mc.mpmt.protocol.reliability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import top.wcpe.mc.mpmt.protocol.packet.FragmentPacket;

/**
 * 分片器（可靠性层，平台无关）：把大载荷按 {@code maxChunk} 切成多个 {@link FragmentPacket}。
 *
 * <p>各片携带完整原载荷的 CRC32，供重组后校验。空载荷产出单个空分片（{@code total=1}）。无状态、线程安全。
 */
public final class Fragmenter {

    /**
     * 切片。
     *
     * @param seqId    分片序列号（同一原载荷的各片共享）
     * @param payload  完整原载荷
     * @param maxChunk 单片最大字节数
     * @return 有序分片列表
     */
    public List<FragmentPacket> split(int seqId, byte[] payload, int maxChunk) {
        if (maxChunk <= 0) {
            throw new IllegalArgumentException("maxChunk 必须为正：" + maxChunk);
        }
        int crc = crc32(payload);
        int total = payload.length == 0 ? 1 : (payload.length + maxChunk - 1) / maxChunk;
        List<FragmentPacket> fragments = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int from = index * maxChunk;
            int to = Math.min(from + maxChunk, payload.length);
            byte[] chunk = Arrays.copyOfRange(payload, from, to);
            fragments.add(new FragmentPacket(seqId, index, total, crc, chunk));
        }
        return fragments;
    }

    /** 计算 CRC32（取低 32 位为 int）。 */
    static int crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }
}
