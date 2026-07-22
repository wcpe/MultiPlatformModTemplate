package top.wcpe.mc.mpmt.protocol.reliability;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/** 有 TTL、组数与总字节上限的出站分片组缓存。 */
public final class OutboundFragmentCache {

    private final long ttlMillis;
    private final int maxGroups;
    private final int maxBytes;
    private final LinkedHashMap<FragmentKey, CachedGroup> groups = new LinkedHashMap<>();
    private int cachedBytes;

    public OutboundFragmentCache(long ttlMillis, int maxGroups, int maxBytes) {
        this.ttlMillis = requirePositive(ttlMillis, "ttlMillis");
        this.maxGroups = requirePositive(maxGroups, "maxGroups");
        this.maxBytes = requirePositive(maxBytes, "maxBytes");
    }

    /** 缓存分片组；单组超过总字节上限时不缓存。 */
    public synchronized void put(
            ConnectionHandle connection, int seqId, List<byte[]> frames, long now) {
        evictExpired(now);
        FragmentKey key = new FragmentKey(connection, seqId);
        remove(key);
        int bytes = frameBytes(frames);
        if (bytes > maxBytes) {
            return;
        }
        makeRoom(bytes);
        groups.put(key, new CachedGroup(frames, bytes, deadline(now, ttlMillis)));
        cachedBytes += bytes;
    }

    /** 取出并消费待重发组，保证同一缓存最多重发一次。 */
    public synchronized List<byte[]> takeForRetry(
            ConnectionHandle connection, int seqId, long now) {
        evictExpired(now);
        CachedGroup group = remove(new FragmentKey(connection, seqId));
        return group == null ? null : group.frames;
    }

    /** 主动清理过期缓存。 */
    public synchronized void evictExpired(long now) {
        Iterator<Map.Entry<FragmentKey, CachedGroup>> iterator = groups.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<FragmentKey, CachedGroup> current = iterator.next();
            if (now >= current.getValue().expiresAt) {
                cachedBytes -= current.getValue().bytes;
                iterator.remove();
            }
        }
    }

    /** 清除指定连接的全部缓存。 */
    public synchronized void clearConnection(ConnectionHandle connection) {
        Iterator<Map.Entry<FragmentKey, CachedGroup>> iterator = groups.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<FragmentKey, CachedGroup> current = iterator.next();
            if (current.getKey().matchesConnection(connection)) {
                cachedBytes -= current.getValue().bytes;
                iterator.remove();
            }
        }
    }

    private void makeRoom(int incomingBytes) {
        Iterator<Map.Entry<FragmentKey, CachedGroup>> iterator = groups.entrySet().iterator();
        while (iterator.hasNext()
                && (groups.size() >= maxGroups || incomingBytes > maxBytes - cachedBytes)) {
            CachedGroup removed = iterator.next().getValue();
            cachedBytes -= removed.bytes;
            iterator.remove();
        }
    }

    private CachedGroup remove(FragmentKey key) {
        CachedGroup removed = groups.remove(key);
        if (removed != null) {
            cachedBytes -= removed.bytes;
        }
        return removed;
    }

    private static int frameBytes(List<byte[]> frames) {
        long bytes = 0L;
        for (byte[] frame : frames) {
            bytes += frame.length;
        }
        return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
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

    /** 连接与序列组成缓存键；连接必须按对象身份比较。 */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static final class FragmentKey {
        private final ConnectionHandle connection;
        private final int seqId;

        FragmentKey(ConnectionHandle connection, int seqId) {
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
            if (!(other instanceof FragmentKey)) {
                return false;
            }
            FragmentKey that = (FragmentKey) other;
            return seqId == that.seqId && connection == that.connection;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(connection) + seqId;
        }
    }

    /** 单个缓存分片组。 */
    private static final class CachedGroup {
        private final List<byte[]> frames;
        private final int bytes;
        private final long expiresAt;

        CachedGroup(List<byte[]> frames, int bytes, long expiresAt) {
            this.frames = new ArrayList<>(frames);
            this.bytes = bytes;
            this.expiresAt = expiresAt;
        }
    }
}
