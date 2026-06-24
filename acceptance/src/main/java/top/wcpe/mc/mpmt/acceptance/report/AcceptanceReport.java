package top.wcpe.mc.mpmt.acceptance.report;

import java.util.List;

/**
 * 单一权威验收报告（ADR-0014）：服务端聚合所有场景结果渲染为纯文本，末行 {@code RESULT PASS|FAIL} 供 Gradle 门禁判定。
 *
 * <p>判定规则：{@code SKIP} 不计入失败，<b>仅当无 FAIL / ERROR 且至少有一个结果</b>才 {@code RESULT PASS}。
 * 报告格式（行分隔）：
 * <pre>
 * SERVER-GAMETEST-REPORT v1
 * PASS &lt;suite&gt;/&lt;id&gt; &lt;durationMs&gt;ms &lt;message&gt;
 * ...
 * TOTAL &lt;n&gt; PASS &lt;p&gt; FAIL &lt;f&gt; ERROR &lt;e&gt; SKIP &lt;s&gt;
 * RESULT PASS
 * </pre>
 */
public final class AcceptanceReport {

    /** 报告首行标识（含版本）。 */
    public static final String HEADER = "SERVER-GAMETEST-REPORT v1";
    /** 末行通过判定。 */
    public static final String RESULT_PASS = "RESULT PASS";
    /** 末行失败判定。 */
    public static final String RESULT_FAIL = "RESULT FAIL";

    private AcceptanceReport() {
        // 工具类不实例化
    }

    /** 是否整体通过：无 FAIL / ERROR 且至少一个结果（空结果视为未通过）。 */
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

    /** 渲染权威报告文本（末行为 {@link #RESULT_PASS} / {@link #RESULT_FAIL}）。 */
    public static String render(List<ScenarioResult> results) {
        StringBuilder report = new StringBuilder();
        report.append(HEADER).append('\n');
        int pass = 0;
        int fail = 0;
        int error = 0;
        int skip = 0;
        for (ScenarioResult result : results) {
            switch (result.getStatus()) {
                case PASS:
                    pass++;
                    break;
                case FAIL:
                    fail++;
                    break;
                case ERROR:
                    error++;
                    break;
                case SKIP:
                    skip++;
                    break;
                default:
                    throw new IllegalStateException("未知场景状态：" + result.getStatus());
            }
            report.append(result.getStatus().name())
                    .append(' ')
                    .append(result.getSuite())
                    .append('/')
                    .append(result.getId())
                    .append(' ')
                    .append(result.getDurationMs())
                    .append("ms ")
                    .append(result.getMessage().replace('\r', ' ').replace('\n', ' '))
                    .append('\n');
        }
        report.append("TOTAL ")
                .append(results.size())
                .append(" PASS ")
                .append(pass)
                .append(" FAIL ")
                .append(fail)
                .append(" ERROR ")
                .append(error)
                .append(" SKIP ")
                .append(skip)
                .append('\n');
        report.append(isPass(results) ? RESULT_PASS : RESULT_FAIL).append('\n');
        return report.toString();
    }
}
