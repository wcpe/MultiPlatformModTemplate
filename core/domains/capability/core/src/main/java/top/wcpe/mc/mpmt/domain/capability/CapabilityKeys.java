package top.wcpe.mc.mpmt.domain.capability;

/**
 * capability 域的持久化键约定（域内共用逻辑）。
 *
 * <p>放在 {@code core}：两端与域内各模块都要按同一套键读写，
 * 属共用逻辑而非任一端专有（ADR-0030 决策 1）。
 */
public final class CapabilityKeys {

    /** 本域持久化命名空间。 */
    public static final String NAMESPACE = "capability-example";

    /** 首次加入时间字段键前缀（拼玩家 UUID）。 */
    public static final String FIRST_JOIN_PREFIX = "first-join:";

    private CapabilityKeys() {
        // 常量持有类不实例化
    }

    /**
     * 拼接首次加入时间的存储键。
     *
     * @param uuid 玩家 UUID 字符串
     * @return 完整存储键
     */
    public static String firstJoinKey(String uuid) {
        return FIRST_JOIN_PREFIX + uuid;
    }
}
