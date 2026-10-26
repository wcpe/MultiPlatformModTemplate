package top.wcpe.mc.mpmt.examples.counter;

/**
 * 计数器持久化键与命名空间约定（示范域 counter 的共用契约）。
 *
 * <p>键格式属于两端必须一致的数据契约，故放 {@code common-api}（ADR-0030）。
 */
public final class CounterKeys {

    /** 本域持久化命名空间。 */
    public static final String NAMESPACE = "counter";

    /** 累计加入次数字段键前缀（拼玩家 UUID）。 */
    public static final String JOIN_COUNT_PREFIX = "join-count:";

    /** 首次加入时间字段键前缀（拼玩家 UUID）。 */
    public static final String FIRST_JOIN_PREFIX = "first-join:";

    private CounterKeys() {
        // 常量持有类不实例化
    }

    /**
     * 拼接累计次数的存储键。
     *
     * @param uuid 玩家 UUID 字符串
     * @return 完整存储键
     */
    public static String joinCountKey(String uuid) {
        return JOIN_COUNT_PREFIX + uuid;
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
