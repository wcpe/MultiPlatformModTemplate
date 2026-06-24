package top.wcpe.mc.mpmt.protocol.reliability;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import top.wcpe.mc.mpmt.protocol.packet.FragmentPacket;

/**
 * 重组器（可靠性层，平台无关，线程安全）：按 {@code seqId} 收集分片，集齐后重组并校验 CRC32。
 *
 * <p>乱序到达可重组；CRC 不符则丢弃；超时未集齐的分组经 {@link #tickTimeouts()} 清理。
 * 时钟经构造注入，便于穷举超时（不在内部直接读系统时钟）。
 */
public final class Reassembler {

    private static final Logger LOGGER = Logger.getLogger(Reassembler.class.getName());

    private final long timeoutMillis;
    private final LongSupplier clock;
    private final Map<Integer, Entry> entries = new ConcurrentHashMap<>();

    public Reassembler(long timeoutMillis, LongSupplier clock) {
        this.timeoutMillis = timeoutMillis;
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    public Reassembler(long timeoutMillis) {
        this(timeoutMillis, System::currentTimeMillis);
    }

    /**
     * 接收一个分片。集齐且 CRC 校验通过则返回完整载荷，否则返回空。
     */
    public Optional<byte[]> accept(FragmentPacket fragment) {
        Objects.requireNonNull(fragment, "fragment 不能为空");
        int seqId = fragment.getSeqId();
        Entry entry = entries.computeIfAbsent(seqId,
                key -> new Entry(fragment.getTotal(), fragment.getCrc32(), clock.getAsLong()));
        boolean complete = entry.put(fragment.getIndex(), fragment.getPayload());
        if (!complete) {
            return Optional.empty();
        }
        entries.remove(seqId);
        byte[] full = entry.assemble();
        if (Fragmenter.crc32(full) != entry.crc) {
            LOGGER.warning("分片重组 CRC 校验失败，丢弃 seqId=" + seqId);
            return Optional.empty();
        }
        return Optional.of(full);
    }

    /** 清理超时未集齐的分组。 */
    public void tickTimeouts() {
        long now = clock.getAsLong();
        entries.entrySet().removeIf(e -> now - e.getValue().firstSeenMillis > timeoutMillis);
    }

    /** 当前待重组的分组数（测试可见）。 */
    int pendingCount() {
        return entries.size();
    }

    /** 单个 seqId 的重组缓冲。 */
    private static final class Entry {
        private final int total;
        private final int crc;
        private final long firstSeenMillis;
        private final byte[][] chunks;
        private int received;
        private boolean completed;

        Entry(int total, int crc, long firstSeenMillis) {
            this.total = Math.max(total, 0);
            this.crc = crc;
            this.firstSeenMillis = firstSeenMillis;
            this.chunks = new byte[this.total][];
        }

        synchronized boolean put(int index, byte[] chunk) {
            if (completed || index < 0 || index >= total) {
                return false;
            }
            if (chunks[index] == null) {
                chunks[index] = chunk;
                received++;
            }
            if (received == total) {
                completed = true;
                return true;
            }
            return false;
        }

        synchronized byte[] assemble() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] chunk : chunks) {
                out.write(chunk, 0, chunk.length);
            }
            return out.toByteArray();
        }
    }
}
