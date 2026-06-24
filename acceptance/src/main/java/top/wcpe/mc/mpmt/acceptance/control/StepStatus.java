package top.wcpe.mc.mpmt.acceptance.control;

/** 客户端单步验证结果状态。线缆码稳定（非 ordinal），未知码解码即拒。 */
public enum StepStatus {

    /** 通过。 */
    OK(1),
    /** 断言失败。 */
    FAIL(2),
    /** 执行异常。 */
    ERROR(3);

    private final int wire;

    StepStatus(int wire) {
        this.wire = wire;
    }

    /** 稳定线缆码。 */
    public int wire() {
        return wire;
    }

    /** 按线缆码还原；未知码抛 {@link IllegalArgumentException}。 */
    public static StepStatus fromWire(int wire) {
        for (StepStatus status : values()) {
            if (status.wire == wire) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知步骤状态线缆码：" + wire);
    }
}
