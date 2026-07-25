package top.wcpe.mc.mpmt.acceptance.report;

import java.util.HashMap;
import java.util.Map;

/** v2 报告与本轮预期的严格校验器。 */
public final class AcceptanceReportV2Validator {

    private AcceptanceReportV2Validator() {
        // 工具类不实例化
    }

    public static AcceptanceReportV2 validate(
            String text, long reportModifiedEpochMs, AcceptanceReportV2Expectation expected) {
        AcceptanceReportV2 report = AcceptanceReportV2Parser.parse(text);
        validateFreshness(reportModifiedEpochMs, expected);
        validateMetadata(report, expected);
        validateArtifacts(report, expected);
        validateScenarios(report, expected);
        ReportTotals recalculated = validateTotals(report);
        validateResult(report, recalculated);
        return report;
    }

    private static void validateFreshness(long reportModifiedEpochMs, AcceptanceReportV2Expectation expected) {
        if (reportModifiedEpochMs < expected.getStartEpochMs()) {
            throw new AcceptanceReportValidationException("报告文件早于本轮启动时间");
        }
    }

    private static void validateMetadata(
            AcceptanceReportV2 report, AcceptanceReportV2Expectation expected) {
        requireEqual("RUN_ID", expected.getRunId(), report.getRunId());
        requireEqual("MATRIX", expected.getMatrix(), report.getMatrix());
        requireEqual("START_EPOCH_MS", expected.getStartEpochMs(), report.getStartEpochMs());
        requireEqual("服务端 Java", expected.getServerJava(), report.getServerJava());
        requireEqual("客户端 Java", expected.getClientJava(), report.getClientJava());
    }

    private static void validateArtifacts(
            AcceptanceReportV2 report, AcceptanceReportV2Expectation expected) {
        Map<String, String> actual = artifactMap(report);
        Map<String, String> wanted = artifactMap(expected);
        if (!wanted.equals(actual)) {
            throw new AcceptanceReportValidationException("制品 role 或 SHA-256 与预期不匹配");
        }
    }

    private static Map<String, String> artifactMap(AcceptanceReportV2 report) {
        Map<String, String> artifacts = new HashMap<>();
        for (ReportArtifact artifact : report.getArtifacts()) {
            if (artifacts.put(artifact.getRole(), artifact.getSha256()) != null) {
                throw new AcceptanceReportValidationException("制品 role 重复：" + artifact.getRole());
            }
        }
        return artifacts;
    }

    private static Map<String, String> artifactMap(AcceptanceReportV2Expectation expected) {
        Map<String, String> artifacts = new HashMap<>();
        for (ReportArtifact artifact : expected.getArtifacts()) {
            artifacts.put(artifact.getRole(), artifact.getSha256());
        }
        return artifacts;
    }

    private static void validateScenarios(
            AcceptanceReportV2 report, AcceptanceReportV2Expectation expected) {
        Map<String, ReportScenario> scenarios = new HashMap<>();
        for (ReportScenario scenario : report.getScenarios()) {
            if (scenarios.put(scenario.getId(), scenario) != null) {
                throw new AcceptanceReportValidationException("场景重复：" + scenario.getId());
            }
            rejectFailure(scenario);
        }
        for (String required : expected.getRequiredScenarios()) {
            ReportScenario scenario = scenarios.get(required);
            if (scenario == null || scenario.getStatus() != ScenarioStatus.PASS) {
                throw new AcceptanceReportValidationException("required 场景未恰好一次 PASS：" + required);
            }
        }
    }

    private static void rejectFailure(ReportScenario scenario) {
        if (scenario.getStatus() == ScenarioStatus.FAIL || scenario.getStatus() == ScenarioStatus.ERROR) {
            throw new AcceptanceReportValidationException(
                    "报告包含 " + scenario.getStatus() + " 场景：" + scenario.getId());
        }
    }

    private static ReportTotals validateTotals(AcceptanceReportV2 report) {
        ReportTotals actual = ReportTotals.from(report.getScenarios());
        if (!actual.equals(report.getTotals())) {
            throw new AcceptanceReportValidationException("TOTAL 与场景记录不一致");
        }
        return actual;
    }

    private static void validateResult(AcceptanceReportV2 report, ReportTotals recalculated) {
        if (report.isResultPass() != recalculated.isPass()) {
            throw new AcceptanceReportValidationException("RESULT 与重算通过语义不一致");
        }
        if (!report.isResultPass()) {
            throw new AcceptanceReportValidationException("报告末行不是 RESULT PASS");
        }
    }

    private static void requireEqual(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AcceptanceReportValidationException(name + " 与预期不匹配");
        }
    }
}
