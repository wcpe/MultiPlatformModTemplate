package top.wcpe.mc.mpmt.acceptance.report;

/** v2 报告的确定性纯文本渲染器。 */
public final class AcceptanceReportV2Renderer {

    public static final String HEADER = "SERVER-GAMETEST-REPORT v2";

    private AcceptanceReportV2Renderer() {
        // 工具类不实例化
    }

    public static String render(AcceptanceReportV2 report) {
        StringBuilder text = new StringBuilder();
        text.append(HEADER).append('\n');
        appendValue(text, "RUN_ID", report.getRunId());
        appendValue(text, "MATRIX", report.getMatrix());
        appendValue(text, "START_EPOCH_MS", Long.toString(report.getStartEpochMs()));
        appendJava(text, "SERVER_JAVA", report.getServerJava());
        appendJava(text, "CLIENT_JAVA", report.getClientJava());
        for (ReportArtifact artifact : report.getArtifacts()) {
            appendArtifact(text, artifact);
        }
        for (ReportScenario scenario : report.getScenarios()) {
            appendScenario(text, scenario);
        }
        text.append(report.getTotals().toReportLine()).append('\n');
        text.append(report.isResultPass() ? AcceptanceReport.RESULT_PASS : AcceptanceReport.RESULT_FAIL).append('\n');
        return text.toString();
    }

    private static void appendValue(StringBuilder text, String key, String value) {
        text.append(key).append('\t').append(value).append('\n');
    }

    private static void appendJava(StringBuilder text, String key, JavaRuntimeInfo javaInfo) {
        text.append(key)
                .append('\t')
                .append(javaInfo.getMajor())
                .append('\t')
                .append(javaInfo.getExecutable())
                .append('\n');
    }

    private static void appendArtifact(StringBuilder text, ReportArtifact artifact) {
        text.append("ARTIFACT\t")
                .append(artifact.getRole())
                .append('\t')
                .append(artifact.getSha256())
                .append('\n');
    }

    private static void appendScenario(StringBuilder text, ReportScenario scenario) {
        text.append("SCENARIO\t")
                .append(scenario.getId())
                .append('\t')
                .append(scenario.getStatus().name())
                .append('\t')
                .append(scenario.getDurationMs())
                .append('\t')
                .append(flatten(scenario.getMessage()))
                .append('\n');
    }

    private static String flatten(String message) {
        return message.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
