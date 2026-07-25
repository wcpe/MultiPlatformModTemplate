package top.wcpe.mc.mpmt.platform.fabric.gametest.client;

import top.wcpe.mc.mpmt.acceptance.control.StepStatus;

/** 客户端验证结论（ADR-0014）：判定后回报给服务端的状态 + 断言数据 + 诊断。 */
public final class VerifyOutcome {

    private final StepStatus status;
    private final String resultJson;
    private final String message;

    private VerifyOutcome(StepStatus status, String resultJson, String message) {
        this.status = status;
        this.resultJson = resultJson;
        this.message = message;
    }

    /** 通过。 */
    public static VerifyOutcome ok(String resultJson, String message) {
        return new VerifyOutcome(StepStatus.OK, resultJson, message);
    }

    /** 断言失败。 */
    public static VerifyOutcome fail(String message) {
        return new VerifyOutcome(StepStatus.FAIL, "{}", message);
    }

    /** 执行异常。 */
    public static VerifyOutcome error(String message) {
        return new VerifyOutcome(StepStatus.ERROR, "{}", message);
    }

    public StepStatus status() {
        return status;
    }

    public String resultJson() {
        return resultJson;
    }

    public String message() {
        return message;
    }
}
