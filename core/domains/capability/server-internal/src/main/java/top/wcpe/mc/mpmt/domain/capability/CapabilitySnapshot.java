package top.wcpe.mc.mpmt.domain.capability;

import java.util.Objects;
import java.util.UUID;

/**
 * 服务端玩家加入状态快照的内部 DTO（capability 域的内部契约）。
 *
 * <p>放在 {@code server-internal}：**域内**服务端侧的数据载体，
 * 只在 {@link CapabilitySnapshotReader} 与其实现之间流动，不进对外发布面（ADR-0030 决策 1/5）。
 */
public final class CapabilitySnapshot {

    private final UUID playerId;
    private final long firstJoinMillis;

    /**
     * 构造。
     *
     * @param playerId        玩家 UUID
     * @param firstJoinMillis 首次加入时间（毫秒）；无记录时为 0
     */
    public CapabilitySnapshot(UUID playerId, long firstJoinMillis) {
        this.playerId = Objects.requireNonNull(playerId, "玩家 UUID 不能为空");
        this.firstJoinMillis = firstJoinMillis;
    }

    /** 玩家 UUID。 */
    public UUID playerId() {
        return playerId;
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
