package top.wcpe.mc.mpmt.acceptance.report;

import java.util.Objects;
import lombok.Value;

/** v2 报告中的单个场景记录。 */
@Value
public class ReportScenario {

    String id;
    ScenarioStatus status;
    long durationMs;
    String message;

    public ReportScenario(String id, ScenarioStatus status, long durationMs, String message) {
        this.id = ReportValueChecks.requireSingleLine("场景 id", id);
        this.status = Objects.requireNonNull(status, "场景状态不能为空");
        this.durationMs = durationMs;
        this.message = Objects.requireNonNull(message, "场景消息不能为空")
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ');
    }
}
