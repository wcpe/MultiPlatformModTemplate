package top.wcpe.mc.mpmt.acceptance.report;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 单一权威验收报告：保留 v1 渲染兼容入口，v2 增加可严格校验的完整元数据。 */
public final class AcceptanceReport {

    /** 旧报告首行，仅用于兼容既有调用方；严格门禁会拒绝。 */
    public static final String HEADER = "SERVER-GAMETEST-REPORT v1";
    /** 当前权威报告首行。 */
    public static final String HEADER_V2 = "SERVER-GAMETEST-REPORT v2";
    /** 末行通过判定。 */
    public static final String RESULT_PASS = "RESULT PASS";
    /** 末行失败判定。 */
    public static final String RESULT_FAIL = "RESULT FAIL";

    private static final String[] REQUIRED_META_KEYS = {
        "commit", "VERSION", "platform", "mcVersion", "serverVersion", "productJarSha256", "scenarios"
    };

    private AcceptanceReport() {
        // 工具类不实例化
    }

    /** 旧版判定：无 FAIL / ERROR 且至少有一个结果。 */
    public static boolean isPass(List<ScenarioResult> results) {
        if (results.isEmpty()) {
            return false;
        }
        for (ScenarioResult result : results) {
            if (result.getStatus() == ScenarioStatus.FAIL || result.getStatus() == ScenarioStatus.ERROR) {
                return false;
            }
        }
        return true;
    }

    /** 权威判定：基础结果通过，且元数据声明的每个适用场景恰好出现一次并为 PASS。 */
    public static boolean isPass(AcceptanceReportMetadata metadata, List<ScenarioResult> results) {
        if (!isPass(results)) {
            return false;
        }
        Map<String, ScenarioStatus> statuses = collectStatuses(results);
        if (statuses == null) {
            return false;
        }
        for (String scenario : metadata.getScenarios()) {
            ScenarioStatus status = findStatus(statuses, scenario);
            if (status != ScenarioStatus.PASS) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, ScenarioStatus> collectStatuses(List<ScenarioResult> results) {
        Map<String, ScenarioStatus> statuses = new HashMap<>();
        for (ScenarioResult result : results) {
            String key = result.getSuite() + "/" + result.getId();
            if (statuses.put(key, result.getStatus()) != null) {
                return null;
            }
        }
        return statuses;
    }

    private static ScenarioStatus findStatus(Map<String, ScenarioStatus> statuses, String scenario) {
        ScenarioStatus exact = statuses.get(scenario);
        if (exact != null || scenario.indexOf('/') >= 0) {
            return exact;
        }
        ScenarioStatus found = null;
        for (Map.Entry<String, ScenarioStatus> entry : statuses.entrySet()) {
            if (entry.getKey().endsWith("/" + scenario)) {
                if (found != null) {
                    return null;
                }
                found = entry.getValue();
            }
        }
        return found;
    }

    /** 保留旧 v1 报告渲染，供尚未迁移的平台代码继续编译；严格门禁不会接受它。 */
    public static String render(List<ScenarioResult> results) {
        return renderBody(HEADER, null, results, isPass(results));
    }

    /** 渲染 v2 权威报告，元数据固定顺序输出，末行仅有一个 RESULT。 */
    public static String render(AcceptanceReportMetadata metadata, List<ScenarioResult> results) {
        return renderBody(HEADER_V2, metadata, results, isPass(metadata, results));
    }

    private static String renderBody(
            String header, AcceptanceReportMetadata metadata, List<ScenarioResult> results, boolean pass) {
        StringBuilder report = new StringBuilder(header).append('\n');
        if (metadata != null) {
            appendMetadata(report, metadata);
        }
        int[] counts = appendResults(report, results);
        appendTotal(report, results.size(), counts);
        report.append(pass ? RESULT_PASS : RESULT_FAIL).append('\n');
        return report.toString();
    }

    private static void appendMetadata(StringBuilder report, AcceptanceReportMetadata metadata) {
        appendMeta(report, "commit", metadata.getCommit());
        appendMeta(report, "VERSION", metadata.getVersion());
        appendMeta(report, "platform", metadata.getPlatform());
        appendMeta(report, "mcVersion", metadata.getMcVersion());
        appendMeta(report, "serverVersion", metadata.getServerVersion());
        appendMeta(report, "productJarSha256", metadata.getProductJarSha256());
        appendMeta(report, "scenarios", String.join(",", metadata.getScenarios()));
    }

    private static void appendMeta(StringBuilder report, String key, String value) {
        report.append("META ").append(key).append('=').append(value).append('\n');
    }

    private static int[] appendResults(StringBuilder report, List<ScenarioResult> results) {
        int[] counts = new int[ScenarioStatus.values().length];
        for (ScenarioResult result : results) {
            counts[result.getStatus().ordinal()]++;
            report.append(result.getStatus().name())
                    .append(' ')
                    .append(result.getSuite())
                    .append('/')
                    .append(result.getId())
                    .append(' ')
                    .append(result.getDurationMs())
                    .append("ms ")
                    .append(flatten(result.getMessage()))
                    .append('\n');
        }
        return counts;
    }

    private static String flatten(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static void appendTotal(StringBuilder report, int total, int[] counts) {
        report.append("TOTAL ")
                .append(total)
                .append(" PASS ")
                .append(counts[ScenarioStatus.PASS.ordinal()])
                .append(" FAIL ")
                .append(counts[ScenarioStatus.FAIL.ordinal()])
                .append(" ERROR ")
                .append(counts[ScenarioStatus.ERROR.ordinal()])
                .append(" SKIP ")
                .append(counts[ScenarioStatus.SKIP.ordinal()])
                .append('\n');
    }

    /** 严格校验权威报告：拒绝旧版、缺元数据、重复 RESULT、场景缺失及非 PASS。 */
    public static boolean isAcceptedReport(String report) {
        if (report == null) {
            return false;
        }
        String[] lines = report.split("\\r?\\n", -1);
        if (lines.length < 2 || !HEADER_V2.equals(lines[0])) {
            return false;
        }
        ParsedReport parsed = parse(lines);
        return parsed != null
                && parsed.hasAllMetadata()
                && parsed.resultCount == 1
                && RESULT_PASS.equals(parsed.result)
                && RESULT_PASS.equals(lastNonEmpty(lines))
                && parsed.requiredScenariosPassed();
    }

    private static ParsedReport parse(String[] lines) {
        ParsedReport parsed = new ParsedReport();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("META ")) {
                if (!parsed.addMetadata(line.substring(5))) {
                    return null;
                }
            } else if (line.startsWith("RESULT ")) {
                parsed.result = line;
                parsed.resultCount++;
            } else if (isScenarioLine(line) && !parsed.addScenario(line)) {
                return null;
            }
        }
        return parsed;
    }

    private static boolean isScenarioLine(String line) {
        for (ScenarioStatus status : ScenarioStatus.values()) {
            if (line.startsWith(status.name() + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String lastNonEmpty(String[] lines) {
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isEmpty()) {
                return lines[i];
            }
        }
        return "";
    }

    private static final class ParsedReport {

        private final Map<String, String> metadata = new HashMap<>();
        private final Map<String, ScenarioStatus> scenarios = new HashMap<>();
        private String result;
        private int resultCount;

        private boolean addMetadata(String entry) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                return false;
            }
            String key = entry.substring(0, separator);
            String value = entry.substring(separator + 1);
            return metadata.put(key, value) == null;
        }

        private boolean addScenario(String line) {
            String[] parts = line.split(" ", 3);
            if (parts.length < 2) {
                return false;
            }
            ScenarioStatus status = ScenarioStatus.valueOf(parts[0]);
            return scenarios.put(parts[1], status) == null;
        }

        private boolean hasAllMetadata() {
            for (String key : REQUIRED_META_KEYS) {
                if (!metadata.containsKey(key) || metadata.get(key).trim().isEmpty()) {
                    return false;
                }
            }
            return metadata.get("productJarSha256").matches("[0-9a-fA-F]{64}");
        }

        private boolean requiredScenariosPassed() {
            String[] required = metadata.get("scenarios").split(",", -1);
            Set<String> unique = new HashSet<>();
            for (String scenario : required) {
                if (scenario.isEmpty() || !unique.add(scenario) || findStatus(scenarios, scenario) != ScenarioStatus.PASS) {
                    return false;
                }
            }
            return true;
        }
    }
}
