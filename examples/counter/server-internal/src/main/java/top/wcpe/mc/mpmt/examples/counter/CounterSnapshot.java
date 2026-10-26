package top.wcpe.mc.mpmt.examples.counter;

import java.util.Objects;
import java.util.UUID;

/**
 * 服务端计数快照的内部 DTO（示范域 counter 的内部契约）。
 *
 * <p>放在 {@code server-internal}：**域内**服务端侧的数据载体，
 * 只在 {@link CounterSnapshotReader} 与其实现之间流动，不进对外发布面（ADR-0030 决策 1/5）。
 *
 * <p>把"次数 + 首次加入时间"打包成一件事：分开取会让调用方承担两次读取与一致性责任。
 */
public final class CounterSnapshot {

    private final UUID playerId;
    private final int joinCount;
    private final long firstJoinMillis;

    /**
     * 构造。
     *
     * @param playerId        玩家 UUID
     * @param joinCount       累计加入次数
     * @param firstJoinMillis 首次加入时间（毫秒）；无记录时为 0
     */
    public CounterSnapshot(UUID playerId, int joinCount, long firstJoinMillis) {
        this.playerId = Objects.requireNonNull(playerId, "玩家 UUID 不能为空");
        this.joinCount = joinCount;
        this.firstJoinMillis = firstJoinMillis;
    }

    /** 玩家 UUID。 */
    public UUID playerId() {
        return playerId;
    }

    /** 累计加入次数。 */
    public int joinCount() {
        return joinCount;
    }

    /** 首次加入时间（毫秒）；0 表示无记录。 */
    public long firstJoinMillis() {
        return firstJoinMillis;
    }

    /** 是否为首次加入（尚无历史记录）。 */
    public boolean firstJoin() {
        return firstJoinMillis <= 0L;
    }
}
