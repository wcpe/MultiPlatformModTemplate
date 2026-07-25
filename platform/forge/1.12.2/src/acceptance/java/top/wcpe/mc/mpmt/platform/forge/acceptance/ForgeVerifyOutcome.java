package top.wcpe.mc.mpmt.platform.forge.acceptance;

import top.wcpe.mc.mpmt.acceptance.control.StepStatus;

/** 客户端步骤验证结论。 */
final class ForgeVerifyOutcome {

    private final StepStatus status;
    private final String resultJson;
    private final String message;

    private ForgeVerifyOutcome(StepStatus status, String resultJson, String message) {
        this.status = status;
        this.resultJson = resultJson;
        this.message = message;
    }

    static ForgeVerifyOutcome ok(String resultJson, String message) {
        return new ForgeVerifyOutcome(StepStatus.OK, resultJson, message);
    }

    static ForgeVerifyOutcome fail(String message) {
        return new ForgeVerifyOutcome(StepStatus.FAIL, "{}", message);
    }

    static ForgeVerifyOutcome error(String message) {
        return new ForgeVerifyOutcome(StepStatus.ERROR, "{}", message);
    }

    StepStatus status() {
        return status;
    }

    String resultJson() {
        return resultJson;
    }

    String message() {
        return message;
    }
}
