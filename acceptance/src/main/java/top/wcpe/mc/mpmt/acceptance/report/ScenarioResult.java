package top.wcpe.mc.mpmt.acceptance.report;

import lombok.NonNull;
import lombok.Value;

/** 单个验收场景的执行结果（一行报告）。 */
@Value
public class ScenarioResult {

    /** 套件名。 */
    @NonNull
    String suite;

    /** 场景 id。 */
    @NonNull
    String id;

    /** 结果状态。 */
    @NonNull
    ScenarioStatus status;

    /** 耗时（毫秒）。 */
    long durationMs;

    /** 诊断信息（换行会被压平为空格以保单行）。 */
    @NonNull
    String message;
}
