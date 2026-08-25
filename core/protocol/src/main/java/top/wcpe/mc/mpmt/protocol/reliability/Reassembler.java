package top.wcpe.mc.mpmt.protocol.reliability;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.protocol.packet.FragmentPacket;

/**
 * 分片重组器：按连接与 {@code seqId} 隔离分组，校验元数据与 CRC，并限制待重组内存。
 *
 * <p>完成组会短期保留去重标记，避免重发与迟到分片导致原包重复路由。超时由外部主动调用
 * {@link #tickTimeouts()} 推进，处理器可据累计次数请求重发或升级重同步。
 */
public final class Reassembler {

    private static final Logger LOGGER = Logger.getLogger(Reassembler.class.getName());
    private static final long DEFAULT_COMPLETED_TTL_MS = 30_000L;
    private static final int DEFAULT_MAX_TOTAL = 8192;
    private static final int DEFAULT_MAX_FRAGMENT_PAYLOAD_BYTES = 1024 * 1024;
    private static final int DEFAULT_MAX_BUFFERED_BYTES = 8 * 1024 * 1024;
    static final int MAX_PENDING_GROUPS = 128;
    static final int MAX_COMPLETED_GROUPS = 1024;

    /** 分组超时处理器。 */
    @FunctionalInterface
    public interface TimeoutHandler {
        void onTimeout(ConnectionHandle connection, int seqId, int timeoutCount);
    }

    private final long timeoutMillis;
    private final long completedTtlMillis;
    private final int maxTotal;
    private final int maxFragmentPayloadBytes;
    private final int maxBufferedBytes;
    private final LongSupplier clock;
    private final TimeoutHandler timeoutHandler;
    private final Map<GroupKey, Entry> entries = new HashMap<>();
    private final Map<GroupKey, Integer> timeoutCounts = new HashMap<>();
    private final Map<GroupKey, Long> completedUntil = new LinkedHashMap<>();
    private int bufferedBytes;

    public Reassembler(
            long timeoutMillis,
            long completedTtlMillis,
            int maxTotal,
            int maxFragmentPayloadBytes,
            int maxBufferedBytes,
            LongSupplier clock,
            TimeoutHandler timeoutHandler) {
        this.timeoutMillis = requirePositive(timeoutMillis, "timeoutMillis");
        this.completedTtlMillis = requirePositive(completedTtlMillis, "completedTtlMillis");
        this.maxTotal = requirePositive(maxTotal, "maxTotal");
        this.maxFragmentPayloadBytes = requirePositive(maxFragmentPayloadBytes, "maxFragmentPayloadBytes");
        this.maxBufferedBytes = requirePositive(maxBufferedBytes, "maxBufferedBytes");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.timeoutHandler = Objects.requireNonNull(timeoutHandler, "timeoutHandler 不能为空");
    }

    public Reassembler(long timeoutMillis, LongSupplier clock, TimeoutHandler timeoutHandler) {
        this(timeoutMillis, DEFAULT_COMPLETED_TTL_MS, DEFAULT_MAX_TOTAL,
                DEFAULT_MAX_FRAGMENT_PAYLOAD_BYTES, DEFAULT_MAX_BUFFERED_BYTES, clock, timeoutHandler);
    }

    public Reassembler(long timeoutMillis, LongSupplier clock) {
        this(timeoutMillis, clock, (connection, seqId, timeoutCount) -> { });
    }

    public Reassembler(long timeoutMillis) {
        this(timeoutMillis, System::currentTimeMillis);
    }

    /** 兼容无连接上下文的调用。 */
    public Optional<byte[]> accept(FragmentPacket fragment) {
        return accept(null, fragment);
    }

    /** 接收一个分片；集齐且 CRC 正确时返回完整载荷。 */
    public synchronized Optional<byte[]> accept(ConnectionHandle connection, FragmentPacket fragment) {
        Objects.requireNonNull(fragment, "fragment 不能为空");
        long now = clock.getAsLong();
        purgeCompleted(now);
        GroupKey key = new GroupKey(connection, fragment.getSeqId());
        if (completedUntil.containsKey(key)) {
            return Optional.empty();
        }
        if (!valid(fragment)) {
            reject(key, "分片元数据或载荷超过限制");
            return Optional.empty();
        }
        Entry entry = entryFor(key, fragment, now);
        if (entry == null || !reserve(key, entry, fragment)) {
            return Optional.empty();
        }
        return entry.put(fragment.getIndex(), fragment.getPayload())
                ? complete(key, entry, now)
                : Optional.empty();
    }

    /** 主动清理超时分组与过期完成标记。 */
    public void tickTimeouts() {
        List<ExpiredGroup> expired;
        synchronized (this) {
            long now = clock.getAsLong();
            purgeCompleted(now);
            expired = removeExpired(now);
        }
        for (ExpiredGroup group : expired) {
            timeoutHandler.onTimeout(group.key.connection, group.key.seqId, group.timeoutCount);
        }
    }

    /** 清除指定连接的全部重组、超时与去重状态。 */
    public synchronized void clearConnection(ConnectionHandle connection) {
        removeEntriesFor(connection);
        timeoutCounts.keySet().removeIf(key -> key.matchesConnection(connection));
        completedUntil.keySet().removeIf(key -> key.matchesConnection(connection));
    }

    /** 清除指定分组的超时历史，供升级重同步后释放状态。 */
    public synchronized void clearGroup(ConnectionHandle connection, int seqId) {
        GroupKey key = new GroupKey(connection, seqId);
        removeEntry(key);
        timeoutCounts.remove(key);
        completedUntil.remove(key);
    }

    /** 当前待重组的分组数（测试可见）。 */
    synchronized int pendingCount() {
        return entries.size();
    }

    /** 当前待重组载荷字节数（测试可见）。 */
    synchronized int bufferedBytes() {
        return bufferedBytes;
    }

    /** 当前完成去重标记数（测试可见）。 */
    synchronized int completedCount() {
        return completedUntil.size();
    }

    private Entry entryFor(GroupKey key, FragmentPacket fragment, long now) {
        Entry entry = entries.get(key);
        if (entry != null && !entry.matches(fragment)) {
            reject(key, "分片 total/crc 元数据不一致");
            return null;
        }
        if (entry == null) {
            if (entries.size() >= MAX_PENDING_GROUPS) {
                reject(key, "待重组分组数超过限制");
                return null;
            }
            entry = new Entry(fragment.getTotal(), fragment.getCrc32(), now);
            entries.put(key, entry);
        }
        return entry;
    }

    private boolean reserve(GroupKey key, Entry entry, FragmentPacket fragment) {
        int additional = entry.additionalBytes(fragment.getIndex(), fragment.getPayload());
        if (additional > maxBufferedBytes - bufferedBytes) {
            reject(key, "分片总缓冲超过限制");
            return false;
        }
        bufferedBytes += additional;
        return true;
    }

    private boolean valid(FragmentPacket fragment) {
        return fragment.getTotal() > 0
                && fragment.getTotal() <= maxTotal
                && fragment.getIndex() >= 0
                && fragment.getIndex() < fragment.getTotal()
                && fragment.getPayload() != null
                && fragment.getPayload().length <= maxFragmentPayloadBytes;
    }

    private Optional<byte[]> complete(GroupKey key, Entry entry, long now) {
        removeEntry(key);
        timeoutCounts.remove(key);
        byte[] full = entry.assemble();
        if (Fragmenter.crc32(full) != entry.crc) {
            LOGGER.warning("分片重组 CRC 校验失败，丢弃 seqId=" + key.seqId);
            return Optional.empty();
        }
        completedUntil.put(key, deadline(now, completedTtlMillis));
        trimCompleted();
        return Optional.of(full);
    }

    private void reject(GroupKey key, String reason) {
        removeEntry(key);
        timeoutCounts.remove(key);
        LOGGER.warning(reason + "，丢弃 seqId=" + key.seqId);
    }

    private List<ExpiredGroup> removeExpired(long now) {
        List<ExpiredGroup> expired = new ArrayList<>();
        Iterator<Map.Entry<GroupKey, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<GroupKey, Entry> current = iterator.next();
            if (now - current.getValue().firstSeenMillis > timeoutMillis) {
                bufferedBytes -= current.getValue().bufferedBytes;
                iterator.remove();
                int count = timeoutCounts.getOrDefault(current.getKey(), 0) + 1;
                timeoutCounts.put(current.getKey(), count);
                expired.add(new ExpiredGroup(current.getKey(), count));
            }
        }
        return expired;
    }

    private void purgeCompleted(long now) {
        completedUntil.entrySet().removeIf(entry -> now >= entry.getValue());
    }

    private void trimCompleted() {
        Iterator<Map.Entry<GroupKey, Long>> iterator = completedUntil.entrySet().iterator();
        while (completedUntil.size() > MAX_COMPLETED_GROUPS && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private void removeEntriesFor(ConnectionHandle connection) {
        Iterator<Map.Entry<GroupKey, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<GroupKey, Entry> current = iterator.next();
            if (current.getKey().matchesConnection(connection)) {
                bufferedBytes -= current.getValue().bufferedBytes;
                iterator.remove();
            }
        }
    }

    private void removeEntry(GroupKey key) {
        Entry removed = entries.remove(key);
        if (removed != null) {
            bufferedBytes -= removed.bufferedBytes;
        }
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必须为正数");
        }
        return value;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " 必须为正数");
        }
        return value;
    }

    private static long deadline(long now, long ttl) {
        return now > Long.MAX_VALUE - ttl ? Long.MAX_VALUE : now + ttl;
    }

    /** 连接与序列共同组成分组键；连接必须按对象身份比较。 */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static final class GroupKey {
        private final ConnectionHandle connection;
        private final int seqId;

        GroupKey(ConnectionHandle connection, int seqId) {
            this.connection = connection;
            this.seqId = seqId;
        }

        boolean matchesConnection(ConnectionHandle other) {
            return connection == other;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof GroupKey)) {
                return false;
            }
            GroupKey that = (GroupKey) other;
            return seqId == that.seqId && connection == that.connection;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(connection) + seqId;
        }
    }

    /** 已超时分组及累计次数。 */
    private static final class ExpiredGroup {
        private final GroupKey key;
        private final int timeoutCount;

        ExpiredGroup(GroupKey key, int timeoutCount) {
            this.key = key;
            this.timeoutCount = timeoutCount;
        }
    }

    /** 单个连接与序列的重组缓冲。 */
    private static final class Entry {
        private final int total;
        private final int crc;
        private final long firstSeenMillis;
        private final byte[][] chunks;
        private int received;
        private int bufferedBytes;

        Entry(int total, int crc, long firstSeenMillis) {
            this.total = total;
            this.crc = crc;
            this.firstSeenMillis = firstSeenMillis;
            this.chunks = new byte[total][];
        }

        boolean matches(FragmentPacket fragment) {
            return total == fragment.getTotal() && crc == fragment.getCrc32();
        }

        int additionalBytes(int index, byte[] payload) {
            return chunks[index] == null ? payload.length : 0;
        }

        boolean put(int index, byte[] payload) {
            if (chunks[index] == null) {
                chunks[index] = payload;
                bufferedBytes += payload.length;
                received++;
            }
            return received == total;
        }

        byte[] assemble() {
            ByteArrayOutputStream out = new ByteArrayOutputStream(bufferedBytes);
            for (byte[] chunk : chunks) {
                out.write(chunk, 0, chunk.length);
            }
            return out.toByteArray();
        }
    }
}
