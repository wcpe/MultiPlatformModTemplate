package top.wcpe.mc.mpmt.acceptance.report;

/** 单个验收场景的结果状态。{@link #SKIP} 不计入失败。 */
public enum ScenarioStatus {
    PASS,
    FAIL,
    ERROR,
    SKIP
}
