package top.wcpe.mc.mpmt.examples.counter;

/**
 * counter 域的服务端配置模型（示范域）。
 *
 * <p>放在 {@code server-internal}：它是域自己的内部 DTO，不对外暴露（ADR-0030）。
 *
 * <p>字段私有 + 访问器（与 {@link CounterSnapshot} 同风格）：snakeyaml 以
 * {@code BeanAccess.FIELD} 按**字段名**映射，反射直接写私有字段，不需要 setter；
 * 而公开可变原始类型字段会被 SpotBugs 判为 {@code PA_PUBLIC_PRIMITIVE_ATTRIBUTE}。
 *
 * <p>所有字段都带缺省值：配置文件缺失或某个键没写时，行为与引入配置之前一致，
 * 不会因为“没写配置”就跑不起来。
 */
public final class CounterConfig {

    /** 缺省：周期提示首次延迟（tick）。 */
    public static final long DEFAULT_REMINDER_DELAY_TICKS = 20L;

    /** 缺省：周期提示间隔（tick）。 */
    public static final long DEFAULT_REMINDER_PERIOD_TICKS = 1200L;

    /** 周期提示首次延迟（tick）。非正数会在 {@link #normalized()} 里回落缺省值。 */
    private long reminderDelayTicks = DEFAULT_REMINDER_DELAY_TICKS;

    /** 周期提示间隔（tick）。非正数会在 {@link #normalized()} 里回落缺省值。 */
    private long reminderPeriodTicks = DEFAULT_REMINDER_PERIOD_TICKS;

    /** 供 snakeyaml 实例化用的无参构造（字段默认值即缺省配置）。 */
    public CounterConfig() {
        // 无需初始化：字段声明处已给缺省值
    }

    /**
     * 以显式值构造（供代码内直接给定，如测试与平台默认值）。
     *
     * @param reminderDelayTicks  周期提示首次延迟（tick）
     * @param reminderPeriodTicks 周期提示间隔（tick）
     */
    public CounterConfig(long reminderDelayTicks, long reminderPeriodTicks) {
        this.reminderDelayTicks = reminderDelayTicks;
        this.reminderPeriodTicks = reminderPeriodTicks;
    }

    /** 周期提示首次延迟（tick）。 */
    public long reminderDelayTicks() {
        return reminderDelayTicks;
    }

    /** 周期提示间隔（tick）。 */
    public long reminderPeriodTicks() {
        return reminderPeriodTicks;
    }

    /**
     * 校验并修正非法值：周期类参数必须为正，否则回落到缺省值。
     *
     * <p>故意不抛异常：配置写错不应让整个服务起不来，但要在运行期有个确定的兑底行为。
     *
     * @return 归一后的配置（就地修改并返回自身）
     */
    public CounterConfig normalized() {
        if (reminderDelayTicks <= 0L) {
            reminderDelayTicks = DEFAULT_REMINDER_DELAY_TICKS;
        }
        if (reminderPeriodTicks <= 0L) {
            reminderPeriodTicks = DEFAULT_REMINDER_PERIOD_TICKS;
        }
        return this;
    }

    @Override
    public String toString() {
        return "CounterConfig{reminderDelayTicks="
                + reminderDelayTicks
                + ", reminderPeriodTicks="
                + reminderPeriodTicks
                + '}';
    }
}
