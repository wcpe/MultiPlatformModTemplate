package top.wcpe.mc.mpmt.examples.counter;

import java.util.Objects;
import java.util.Optional;
import top.wcpe.mc.mpmt.kernel.port.PersistencePort;

/**
 * 加入次数的读写与自增（示范域 counter 的共用实现）。
 *
 * <p>放在 {@code core}：算法两端共用，不含端差异（ADR-0030）。
 * 持久化经 {@link PersistencePort} 抽象，本类不感知存储形态；调用方应已切到异步线程。
 */
public final class JoinCounter {

    private final PersistencePort persistence;

    /**
     * 构造。
     *
     * @param persistence 持久化端口，不可为空
     */
    public JoinCounter(PersistencePort persistence) {
        this.persistence = Objects.requireNonNull(persistence, "持久化端口不能为空");
    }

    /**
     * 读取累计次数。
     *
     * @param uuid 玩家 UUID 字符串
     * @return 累计次数；无记录或脏数据按 0 处理，避免阻断主流程
     */
    public int current(String uuid) {
        Objects.requireNonNull(uuid, "UUID 不能为空");
        return persistence
                .read(CounterKeys.NAMESPACE, CounterKeys.joinCountKey(uuid))
                .map(JoinCounter::parseCount)
                .orElse(0);
    }

    /**
     * 累计次数加一并写回。
     *
     * @param uuid 玩家 UUID 字符串
     * @return 自增后的次数
     */
    public int increment(String uuid) {
        Objects.requireNonNull(uuid, "UUID 不能为空");
        int next = current(uuid) + 1;
        persistence.write(CounterKeys.NAMESPACE, CounterKeys.joinCountKey(uuid), Integer.toString(next));
        return next;
    }

    /**
     * 首次加入时记录时间戳；已有记录则保持不变。
     *
     * @param uuid      玩家 UUID 字符串
     * @param nowMillis 当前毫秒时间
     * @return 首次加入时间是否由本次写入
     */
    public boolean recordFirstJoin(String uuid, long nowMillis) {
        Objects.requireNonNull(uuid, "UUID 不能为空");
        String key = CounterKeys.firstJoinKey(uuid);
        Optional<String> existing = persistence.read(CounterKeys.NAMESPACE, key);
        if (existing.isPresent()) {
            return false;
        }
        persistence.write(CounterKeys.NAMESPACE, key, Long.toString(nowMillis));
        return true;
    }

    /**
     * 读取首次加入时间。
     *
     * @param uuid 玩家 UUID 字符串
     * @return 首次加入时间（毫秒）；无记录或脏数据返回 0
     */
    public long firstJoinMillis(String uuid) {
        Objects.requireNonNull(uuid, "UUID 不能为空");
        return persistence
                .read(CounterKeys.NAMESPACE, CounterKeys.firstJoinKey(uuid))
                .map(JoinCounter::parseMillis)
                .orElse(0L);
    }

    /**
     * 把累计次数重置为 0；首次加入时间保持不变。
     *
     * <p>写回 {@code 0} 而不是删键：{@link PersistencePort} 没有删除语义，
     * 且「无记录」与「记录为 0」在 {@link #current} 上都读成 0，行为一致。
     *
     * @param uuid 玩家 UUID 字符串
     */
    public void resetCount(String uuid) {
        Objects.requireNonNull(uuid, "UUID 不能为空");
        persistence.write(CounterKeys.NAMESPACE, CounterKeys.joinCountKey(uuid), "0");
    }

    /** 解析计数；非法值按 0 处理。 */
    static int parseCount(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 解析时间戳；非法值按 0 处理。 */
    static long parseMillis(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
