package top.wcpe.mc.mpmt.acceptance.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** v2 验收报告的不可变聚合模型。 */
public final class AcceptanceReportV2 {

    private final String runId;
    private final String matrix;
    private final long startEpochMs;
    private final JavaRuntimeInfo serverJava;
    private final JavaRuntimeInfo clientJava;
    private final List<ReportArtifact> artifacts;
    private final List<ReportScenario> scenarios;
    private final ReportTotals totals;
    private final boolean resultPass;

    private AcceptanceReportV2(
            String runId,
            String matrix,
            long startEpochMs,
            JavaRuntimeInfo serverJava,
            JavaRuntimeInfo clientJava,
            List<ReportArtifact> artifacts,
            List<ReportScenario> scenarios,
            ReportTotals totals,
            boolean resultPass) {
        this.runId = ReportValueChecks.requireSingleLine("RUN_ID", runId);
        this.matrix = ReportValueChecks.requireSingleLine("MATRIX", matrix);
        if (startEpochMs < 0L) {
            throw new IllegalArgumentException("START_EPOCH_MS 不能为负数");
        }
        this.startEpochMs = startEpochMs;
        this.serverJava = Objects.requireNonNull(serverJava, "服务端 Java 信息不能为空");
        this.clientJava = Objects.requireNonNull(clientJava, "客户端 Java 信息不能为空");
        this.artifacts = immutableCopy(artifacts, "制品列表");
        this.scenarios = immutableCopy(scenarios, "场景列表");
        this.totals = Objects.requireNonNull(totals, "TOTAL 不能为空");
        this.resultPass = resultPass;
    }

    public static AcceptanceReportV2 create(
            String runId,
            String matrix,
            long startEpochMs,
            JavaRuntimeInfo serverJava,
            JavaRuntimeInfo clientJava,
            List<ReportArtifact> artifacts,
            List<ReportScenario> scenarios) {
        ReportTotals totals = ReportTotals.from(scenarios);
        return new AcceptanceReportV2(
                runId,
                matrix,
                startEpochMs,
                serverJava,
                clientJava,
                artifacts,
                scenarios,
                totals,
                totals.isPass());
    }

    static AcceptanceReportV2 parsed(
            String runId,
            String matrix,
            long startEpochMs,
            JavaRuntimeInfo serverJava,
            JavaRuntimeInfo clientJava,
            List<ReportArtifact> artifacts,
            List<ReportScenario> scenarios,
            ReportTotals totals,
            boolean resultPass) {
        return new AcceptanceReportV2(
                runId,
                matrix,
                startEpochMs,
                serverJava,
                clientJava,
                artifacts,
                scenarios,
                totals,
                resultPass);
    }

    private static <T> List<T> immutableCopy(List<T> values, String name) {
        Objects.requireNonNull(values, name + "不能为空");
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + "不能包含 null"));
        }
        return Collections.unmodifiableList(copy);
    }

    public String getRunId() {
        return runId;
    }

    public String getMatrix() {
        return matrix;
    }

    public long getStartEpochMs() {
        return startEpochMs;
    }

    public JavaRuntimeInfo getServerJava() {
        return serverJava;
    }

    public JavaRuntimeInfo getClientJava() {
        return clientJava;
    }

    public List<ReportArtifact> getArtifacts() {
        return ReportValueChecks.immutableSnapshot(artifacts);
    }

    public List<ReportScenario> getScenarios() {
        return ReportValueChecks.immutableSnapshot(scenarios);
    }

    public ReportTotals getTotals() {
        return totals;
    }

    public boolean isResultPass() {
        return resultPass;
    }
}
