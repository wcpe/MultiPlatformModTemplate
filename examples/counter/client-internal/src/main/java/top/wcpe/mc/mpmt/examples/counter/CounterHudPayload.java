package top.wcpe.mc.mpmt.examples.counter;

import java.util.Objects;
import java.util.UUID;

/**
 * 客户端渲染用的内部 DTO（示范域 counter 的内部契约）。
 *
 * <p>放在 {@code client-internal}：它是**域内**客户端侧的数据载体，
 * 只在 {@link CounterHudRenderer} 与其实现之间流动，不进对外发布面（ADR-0030 决策 1/5）。
 *
 * <p>为什么要这个 DTO 而不是直接传 {@code PlayerRef + int}：内部接口一旦把多个裸参数
 * 摊在签名上，后续加字段就是破坏性变更；用不可变 DTO 承载，调用方只依赖结构不依赖顺序。
 */
public final class CounterHudPayload {

    private final UUID playerId;
    private final String playerName;
    private final int joinCount;

    /**
     * 构造。
     *
     * @param playerId   玩家 UUID
     * @param playerName 玩家名（展示用）
     * @param joinCount  累计加入次数
     */
    public CounterHudPayload(UUID playerId, String playerName, int joinCount) {
        this.playerId = Objects.requireNonNull(playerId, "玩家 UUID 不能为空");
        this.playerName = Objects.requireNonNull(playerName, "玩家名不能为空");
        this.joinCount = joinCount;
    }

    /** 玩家 UUID。 */
    public UUID playerId() {
        return playerId;
    }

    /** 玩家名。 */
    public String playerName() {
        return playerName;
    }

    /** 累计加入次数。 */
    public int joinCount() {
        return joinCount;
    }

    /** 渲染文本：域内约定同一条措辞，避免各实现各写一份。 */
    public String displayText() {
        return playerName + " 已加入 " + joinCount + " 次";
    }
}
