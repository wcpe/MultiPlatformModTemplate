package top.wcpe.mc.mpmt.platform.neoforge.acceptance.client;

/** 客户端验证步骤（ADR-0014）：服务端下发的一步，携带步骤 id / 泛化参数 / 本步已轮询的 tick 数。 */
public final class VerifyStep {

    private final String stepId;
    private final String paramsJson;
    private final int ticksInStep;

    public VerifyStep(String stepId, String paramsJson, int ticksInStep) {
        this.stepId = stepId;
        this.paramsJson = paramsJson;
        this.ticksInStep = ticksInStep;
    }

    public String stepId() {
        return stepId;
    }

    public String paramsJson() {
        return paramsJson;
    }

    /** 本步骤已轮询的 tick 数（首次为 0），供验证器做客户端侧超时判定。 */
    public int ticksInStep() {
        return ticksInStep;
    }
}
