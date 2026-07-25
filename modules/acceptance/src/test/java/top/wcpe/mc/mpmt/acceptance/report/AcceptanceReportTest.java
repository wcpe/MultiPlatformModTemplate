package top.wcpe.mc.mpmt.acceptance.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 单一权威报告聚合与 RESULT 判定（ADR-0014）。 */
class AcceptanceReportTest {

    private static ScenarioResult res(String id, ScenarioStatus status, String message, long durationMs) {
        return new ScenarioResult("acceptance", id, status, durationMs, message);
    }

    private static AcceptanceReportMetadata metadata(List<String> scenarios) {
        return new AcceptanceReportMetadata(
                "0123456789abcdef",
                "0.1.0",
                "fabric",
                "1.20.1",
                "Fabric Server 1.20.1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                scenarios);
    }

    @Test
    @DisplayName("全通过：RESULT PASS，TOTAL 统计正确，行格式正确")
    void 全通过() {
        List<ScenarioResult> results =
                Arrays.asList(res("a", ScenarioStatus.PASS, "", 123), res("b", ScenarioStatus.PASS, "", 45));
        assertTrue(AcceptanceReport.isPass(results));
        String report = AcceptanceReport.render(results);
        assertTrue(report.startsWith(AcceptanceReport.HEADER + "\n"));
        assertTrue(report.contains("PASS acceptance/a 123ms"), report);
        assertTrue(report.contains("TOTAL 2 PASS 2 FAIL 0 ERROR 0 SKIP 0"), report);
        assertTrue(report.endsWith(AcceptanceReport.RESULT_PASS + "\n"), report);
    }

    @Test
    @DisplayName("含 SKIP 不计失败：仍 RESULT PASS")
    void 含skip仍通过() {
        List<ScenarioResult> results =
                Arrays.asList(res("a", ScenarioStatus.PASS, "", 1), res("b", ScenarioStatus.SKIP, "未装配", 2));
        assertTrue(AcceptanceReport.isPass(results));
        String report = AcceptanceReport.render(results);
        assertTrue(report.contains("TOTAL 2 PASS 1 FAIL 0 ERROR 0 SKIP 1"), report);
        assertTrue(report.endsWith(AcceptanceReport.RESULT_PASS + "\n"));
    }

    @Test
    @DisplayName("含 FAIL：RESULT FAIL")
    void 含fail失败() {
        List<ScenarioResult> results =
                Arrays.asList(res("a", ScenarioStatus.PASS, "", 1), res("b", ScenarioStatus.FAIL, "断言失败", 2));
        assertFalse(AcceptanceReport.isPass(results));
        assertTrue(AcceptanceReport.render(results).endsWith(AcceptanceReport.RESULT_FAIL + "\n"));
    }

    @Test
    @DisplayName("含 ERROR：RESULT FAIL")
    void 含error失败() {
        List<ScenarioResult> results = Collections.singletonList(res("a", ScenarioStatus.ERROR, "异常", 1));
        assertFalse(AcceptanceReport.isPass(results));
        assertTrue(AcceptanceReport.render(results).endsWith(AcceptanceReport.RESULT_FAIL + "\n"));
    }

    @Test
    @DisplayName("空结果：视为未通过 RESULT FAIL")
    void 空结果失败() {
        List<ScenarioResult> results = Collections.emptyList();
        assertFalse(AcceptanceReport.isPass(results));
        String report = AcceptanceReport.render(results);
        assertTrue(report.contains("TOTAL 0 PASS 0 FAIL 0 ERROR 0 SKIP 0"), report);
        assertTrue(report.endsWith(AcceptanceReport.RESULT_FAIL + "\n"));
    }

    @Test
    @DisplayName("消息换行被压平为空格以保单行")
    void 消息压平() {
        String report = AcceptanceReport.render(Collections.singletonList(res("a", ScenarioStatus.FAIL, "第一行\n第二行", 1)));
        assertTrue(report.contains("第一行 第二行"), report);
        // 报告行数 = 头 + 1 结果 + TOTAL + RESULT = 4 行（消息内换行不得引入额外行）
        assertEquals(4, report.trim().split("\n").length, report);
    }

    @Test
    @DisplayName("权威报告包含完整元数据、场景清单与唯一 RESULT")
    void 权威报告元数据完整() {
        List<String> scenarios = Arrays.asList("handshake-success", "real-round-trip");
        List<ScenarioResult> results = Arrays.asList(
                res("handshake-success", ScenarioStatus.PASS, "", 1),
                res("real-round-trip", ScenarioStatus.PASS, "", 2));

        String report = AcceptanceReport.render(metadata(scenarios), results);

        assertTrue(report.startsWith(AcceptanceReport.HEADER_V2 + "\n"), report);
        assertTrue(report.contains("META commit=0123456789abcdef\n"), report);
        assertTrue(report.contains("META VERSION=0.1.0\n"), report);
        assertTrue(report.contains("META platform=fabric\n"), report);
        assertTrue(report.contains("META mcVersion=1.20.1\n"), report);
        assertTrue(report.contains("META serverVersion=Fabric Server 1.20.1\n"), report);
        assertTrue(report.contains("META productJarSha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n"), report);
        assertTrue(report.contains("META scenarios=handshake-success,real-round-trip\n"), report);
        assertEquals(1, countResultLines(report));
        assertTrue(AcceptanceReport.isAcceptedReport(report));
    }

    @Test
    @DisplayName("旧报告、缺元数据、重复 RESULT 与非 PASS 报告均拒绝")
    void 严格拒绝无效报告() {
        List<String> scenarios = Collections.singletonList("handshake-success");
        List<ScenarioResult> pass = Collections.singletonList(res("handshake-success", ScenarioStatus.PASS, "", 1));
        String valid = AcceptanceReport.render(metadata(scenarios), pass);

        assertFalse(AcceptanceReport.isAcceptedReport(AcceptanceReport.render(pass)), "旧 v1 报告必须拒绝");
        assertFalse(
                AcceptanceReport.isAcceptedReport(valid.replace("META commit=0123456789abcdef\n", "")),
                "缺元数据必须拒绝");
        assertFalse(AcceptanceReport.isAcceptedReport(valid + AcceptanceReport.RESULT_PASS + "\n"), "重复 RESULT 必须拒绝");

        List<ScenarioResult> fail = Collections.singletonList(res("handshake-success", ScenarioStatus.FAIL, "失败", 1));
        assertFalse(AcceptanceReport.isAcceptedReport(AcceptanceReport.render(metadata(scenarios), fail)));
    }

    @Test
    @DisplayName("聚合器发现元数据声明的适用场景缺失即失败")
    void 缺适用场景失败() {
        AcceptanceReportMetadata metadata = metadata(Arrays.asList("handshake-success", "real-round-trip"));
        List<ScenarioResult> incomplete =
                Collections.singletonList(res("handshake-success", ScenarioStatus.PASS, "", 1));

        assertFalse(AcceptanceReport.isPass(metadata, incomplete));
        assertTrue(AcceptanceReport.render(metadata, incomplete).endsWith(AcceptanceReport.RESULT_FAIL + "\n"));
    }

    private static int countResultLines(String report) {
        int count = 0;
        for (String line : report.split("\\r?\\n")) {
            if (line.startsWith("RESULT ")) {
                count++;
            }
        }
        return count;
    }
}
