package top.wcpe.mc.mpmt.acceptance.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** v2 纯文本报告解析器。 */
public final class AcceptanceReportV2Parser {

    static final int MAX_REPORT_CHARACTERS = 1 << 20;
    static final int MAX_REPORT_LINES = 2048;
    static final int MAX_LINE_CHARACTERS = 8192;
    static final int MAX_ARTIFACTS = 64;
    static final int MAX_SCENARIOS = 1024;

    private AcceptanceReportV2Parser() {
        // 工具类不实例化
    }

    public static AcceptanceReportV2 parse(String text) {
        List<String> lines = reportLines(text);
        requireHeader(lines);
        requireResultLast(lines);
        ParseState state = new ParseState();
        for (int index = 1; index < lines.size(); index++) {
            parseLine(state, lines.get(index), index + 1);
        }
        return state.build();
    }

    private static List<String> reportLines(String text) {
        Objects.requireNonNull(text, "报告文本不能为空");
        if (text.length() > MAX_REPORT_CHARACTERS) {
            throw new AcceptanceReportValidationException("报告字符数超过上限：" + MAX_REPORT_CHARACTERS);
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] split = normalized.split("\n", -1);
        int length = split.length;
        if (length > 0 && split[length - 1].isEmpty()) {
            length--;
        }
        List<String> lines = Arrays.asList(Arrays.copyOf(split, length));
        requireLineLimits(lines);
        return lines;
    }

    private static void requireLineLimits(List<String> lines) {
        if (lines.size() > MAX_REPORT_LINES) {
            throw new AcceptanceReportValidationException("报告行数超过上限：" + MAX_REPORT_LINES);
        }
        for (String line : lines) {
            if (line.length() > MAX_LINE_CHARACTERS) {
                throw new AcceptanceReportValidationException("报告单行字符数超过上限：" + MAX_LINE_CHARACTERS);
            }
        }
    }

    private static void requireHeader(List<String> lines) {
        if (lines.isEmpty() || !AcceptanceReportV2Renderer.HEADER.equals(lines.get(0))) {
            throw new AcceptanceReportValidationException("报告不是 SERVER-GAMETEST-REPORT v2");
        }
    }

    private static void requireResultLast(List<String> lines) {
        if (lines.size() < 2) {
            throw new AcceptanceReportValidationException("报告缺少末行 RESULT");
        }
        String last = lines.get(lines.size() - 1);
        if (!AcceptanceReport.RESULT_PASS.equals(last) && !AcceptanceReport.RESULT_FAIL.equals(last)) {
            throw new AcceptanceReportValidationException("报告末行必须为 RESULT PASS 或 RESULT FAIL");
        }
    }

    private static void parseLine(ParseState state, String line, int lineNumber) {
        try {
            state.accept(line);
        } catch (AcceptanceReportValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AcceptanceReportValidationException(
                    "报告第 " + lineNumber + " 行非法：" + exception.getMessage(), exception);
        }
    }

    private static final class ParseState {

        private String runId;
        private String matrix;
        private Long startEpochMs;
        private JavaRuntimeInfo serverJava;
        private JavaRuntimeInfo clientJava;
        private final List<ReportArtifact> artifacts = new ArrayList<>();
        private final List<ReportScenario> scenarios = new ArrayList<>();
        private ReportTotals totals;
        private Boolean resultPass;

        void accept(String line) {
            if (line.startsWith("RUN_ID\t")) {
                runId = unique("RUN_ID", runId, twoFields(line, "RUN_ID")[1]);
            } else if (line.startsWith("MATRIX\t")) {
                matrix = unique("MATRIX", matrix, twoFields(line, "MATRIX")[1]);
            } else if (line.startsWith("START_EPOCH_MS\t")) {
                parseStart(line);
            } else if (line.startsWith("SERVER_JAVA\t")) {
                serverJava = unique("SERVER_JAVA", serverJava, javaInfo(line, "SERVER_JAVA"));
            } else if (line.startsWith("CLIENT_JAVA\t")) {
                clientJava = unique("CLIENT_JAVA", clientJava, javaInfo(line, "CLIENT_JAVA"));
            } else if (line.startsWith("ARTIFACT\t")) {
                addArtifact(line);
            } else if (line.startsWith("SCENARIO\t")) {
                addScenario(line);
            } else if (line.startsWith("TOTAL ")) {
                totals = unique("TOTAL", totals, totals(line));
            } else if (line.startsWith("RESULT ")) {
                parseResult(line);
            } else {
                throw new AcceptanceReportValidationException("未知报告行：" + line);
            }
        }

        private void addArtifact(String line) {
            if (artifacts.size() >= MAX_ARTIFACTS) {
                throw new AcceptanceReportValidationException("ARTIFACT 数量超过上限：" + MAX_ARTIFACTS);
            }
            artifacts.add(artifact(line));
        }

        private void addScenario(String line) {
            if (scenarios.size() >= MAX_SCENARIOS) {
                throw new AcceptanceReportValidationException("SCENARIO 数量超过上限：" + MAX_SCENARIOS);
            }
            scenarios.add(scenario(line));
        }

        private void parseStart(String line) {
            String value = twoFields(line, "START_EPOCH_MS")[1];
            startEpochMs = unique("START_EPOCH_MS", startEpochMs, Long.parseLong(value));
        }

        private void parseResult(String line) {
            boolean pass;
            if (AcceptanceReport.RESULT_PASS.equals(line)) {
                pass = true;
            } else if (AcceptanceReport.RESULT_FAIL.equals(line)) {
                pass = false;
            } else {
                throw new AcceptanceReportValidationException("RESULT 只能为 PASS 或 FAIL");
            }
            resultPass = unique("RESULT", resultPass, pass);
        }

        AcceptanceReportV2 build() {
            requirePresent("RUN_ID", runId);
            requirePresent("MATRIX", matrix);
            requirePresent("START_EPOCH_MS", startEpochMs);
            requirePresent("SERVER_JAVA", serverJava);
            requirePresent("CLIENT_JAVA", clientJava);
            requirePresent("TOTAL", totals);
            requirePresent("RESULT", resultPass);
            return AcceptanceReportV2.parsed(
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
    }

    private static String[] twoFields(String line, String key) {
        String[] fields = line.split("\t", -1);
        requireFields(key, fields, 2);
        return fields;
    }

    private static JavaRuntimeInfo javaInfo(String line, String key) {
        String[] fields = line.split("\t", -1);
        requireFields(key, fields, 3);
        return new JavaRuntimeInfo(Integer.parseInt(fields[1]), fields[2]);
    }

    private static ReportArtifact artifact(String line) {
        String[] fields = line.split("\t", -1);
        requireFields("ARTIFACT", fields, 3);
        return new ReportArtifact(fields[1], fields[2]);
    }

    private static ReportScenario scenario(String line) {
        String[] fields = line.split("\t", -1);
        requireFields("SCENARIO", fields, 5);
        ScenarioStatus status = ScenarioStatus.valueOf(fields[2]);
        return new ReportScenario(fields[1], status, Long.parseLong(fields[3]), fields[4]);
    }

    private static ReportTotals totals(String line) {
        String[] fields = line.split(" ", -1);
        if (fields.length != 10
                || !"TOTAL".equals(fields[0])
                || !"PASS".equals(fields[2])
                || !"FAIL".equals(fields[4])
                || !"ERROR".equals(fields[6])
                || !"SKIP".equals(fields[8])) {
            throw new AcceptanceReportValidationException("TOTAL 行格式非法");
        }
        return new ReportTotals(
                Integer.parseInt(fields[1]),
                Integer.parseInt(fields[3]),
                Integer.parseInt(fields[5]),
                Integer.parseInt(fields[7]),
                Integer.parseInt(fields[9]));
    }

    private static void requireFields(String key, String[] fields, int count) {
        if (fields.length != count || !key.equals(fields[0])) {
            throw new AcceptanceReportValidationException(key + " 行格式非法");
        }
    }

    private static <T> T unique(String name, T current, T value) {
        if (current != null) {
            throw new AcceptanceReportValidationException(name + " 重复");
        }
        return value;
    }

    private static void requirePresent(String name, Object value) {
        if (value == null) {
            throw new AcceptanceReportValidationException("报告缺少 " + name);
        }
    }
}
