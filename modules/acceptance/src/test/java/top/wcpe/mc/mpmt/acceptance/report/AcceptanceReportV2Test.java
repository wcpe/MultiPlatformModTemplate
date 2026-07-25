package top.wcpe.mc.mpmt.acceptance.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** v2 报告模型、渲染与解析契约。 */
class AcceptanceReportV2Test {

    private static final String SERVER_HASH =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String CLIENT_HASH =
            "2222222222222222222222222222222222222222222222222222222222222222";

    @Test
    @DisplayName("v1 API 与文本保持不变")
    void v1兼容性() {
        ScenarioResult result = new ScenarioResult("acceptance", "a", ScenarioStatus.PASS, 1L, "");

        assertEquals(
                "SERVER-GAMETEST-REPORT v1\n"
                        + "PASS acceptance/a 1ms \n"
                        + "TOTAL 1 PASS 1 FAIL 0 ERROR 0 SKIP 0\n"
                        + "RESULT PASS\n",
                AcceptanceReport.render(Collections.singletonList(result)));
    }

    @Test
    @DisplayName("v2 渲染包含唯一元数据、双端 Java、制品、场景、TOTAL 与末行 RESULT")
    void v2渲染完整契约() {
        AcceptanceReportV2 report = report();

        String text = AcceptanceReportV2Renderer.render(report);

        assertTrue(text.startsWith("SERVER-GAMETEST-REPORT v2\n"), text);
        assertTrue(text.contains("RUN_ID\trun-1\n"), text);
        assertTrue(text.contains("MATRIX\tR1\n"), text);
        assertTrue(text.contains("START_EPOCH_MS\t1000\n"), text);
        assertTrue(text.contains("SERVER_JAVA\t21\tC:/Java 21/bin/java.exe\n"), text);
        assertTrue(text.contains("CLIENT_JAVA\t21\tC:/Java 21/bin/javaw.exe\n"), text);
        assertTrue(text.contains("ARTIFACT\tserver-runtime\t" + SERVER_HASH + "\n"), text);
        assertTrue(text.contains("SCENARIO\tproduct-handshake\tPASS\t12\t第一行 第二行\n"), text);
        assertTrue(text.contains("TOTAL 2 PASS 1 FAIL 0 ERROR 0 SKIP 1\n"), text);
        assertTrue(text.endsWith("RESULT PASS\n"), text);
    }

    @Test
    @DisplayName("v2 解析与渲染往返保持值和 SKIP 语义")
    void v2解析往返() {
        AcceptanceReportV2 original = report();

        AcceptanceReportV2 parsed = AcceptanceReportV2Parser.parse(AcceptanceReportV2Renderer.render(original));

        assertEquals(original.getRunId(), parsed.getRunId());
        assertEquals(original.getMatrix(), parsed.getMatrix());
        assertEquals(original.getStartEpochMs(), parsed.getStartEpochMs());
        assertEquals(original.getServerJava(), parsed.getServerJava());
        assertEquals(original.getClientJava(), parsed.getClientJava());
        assertEquals(original.getArtifacts(), parsed.getArtifacts());
        assertEquals(original.getScenarios(), parsed.getScenarios());
        assertEquals(original.getTotals(), parsed.getTotals());
        assertEquals(original.isResultPass(), parsed.isResultPass());
    }

    @Test
    @DisplayName("解析拒绝 v1、缺失字段、重复字段和非末行 RESULT")
    void 解析拒绝非法结构() {
        String valid = AcceptanceReportV2Renderer.render(report());

        assertThrows(
                AcceptanceReportValidationException.class,
                () -> AcceptanceReportV2Parser.parse(valid.replace("SERVER-GAMETEST-REPORT v2", "SERVER-GAMETEST-REPORT v1")));
        assertThrows(
                AcceptanceReportValidationException.class,
                () -> AcceptanceReportV2Parser.parse(valid.replace("RUN_ID\trun-1\n", "")));
        assertThrows(
                AcceptanceReportValidationException.class,
                () -> AcceptanceReportV2Parser.parse(valid.replace("MATRIX\tR1\n", "MATRIX\tR1\nMATRIX\tR1\n")));
        assertThrows(
                AcceptanceReportValidationException.class,
                () -> AcceptanceReportV2Parser.parse(valid + "TRAILING\n"));
    }

    @Test
    @DisplayName("v2 模型对构造入参和集合 getter 均执行防御复制")
    void v2模型集合不可变() {
        List<ReportArtifact> artifacts = new ArrayList<>(Arrays.asList(
                new ReportArtifact("server-runtime", SERVER_HASH),
                new ReportArtifact("client-product", CLIENT_HASH)));
        List<ReportScenario> scenarios = new ArrayList<>(Collections.singletonList(
                new ReportScenario("product-handshake", ScenarioStatus.PASS, 1L, "通过")));
        AcceptanceReportV2 report = AcceptanceReportV2.create(
                "run-1",
                "R1",
                1000L,
                new JavaRuntimeInfo(21, "C:/server/java.exe"),
                new JavaRuntimeInfo(21, "C:/client/java.exe"),
                artifacts,
                scenarios);

        artifacts.clear();
        scenarios.clear();
        assertEquals(2, report.getArtifacts().size());
        assertEquals(1, report.getScenarios().size());
        assertNotSame(report.getArtifacts(), report.getArtifacts());
        assertNotSame(report.getScenarios(), report.getScenarios());
        assertThrows(UnsupportedOperationException.class, () -> report.getArtifacts().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.getScenarios().clear());
    }

    @Test
    @DisplayName("v2 预期对构造入参和集合 getter 均执行防御复制")
    void v2预期集合不可变() {
        List<ReportArtifact> artifacts = new ArrayList<>(Arrays.asList(
                new ReportArtifact("server-runtime", SERVER_HASH),
                new ReportArtifact("server-product", SERVER_HASH),
                new ReportArtifact("server-acceptance", SERVER_HASH),
                new ReportArtifact("client-product", CLIENT_HASH),
                new ReportArtifact("client-acceptance", CLIENT_HASH)));
        AcceptanceReportV2Expectation expectation = new AcceptanceReportV2Expectation(
                "run-1",
                "R1",
                1000L,
                new JavaRuntimeInfo(21, "C:/server/java.exe"),
                new JavaRuntimeInfo(21, "C:/client/java.exe"),
                artifacts);

        artifacts.clear();
        assertEquals(5, expectation.getArtifacts().size());
        assertEquals(
                Arrays.asList("product-handshake", "product-roundtrip", "client-hud"),
                expectation.getRequiredScenarios());
        assertNotSame(expectation.getArtifacts(), expectation.getArtifacts());
        assertNotSame(expectation.getRequiredScenarios(), expectation.getRequiredScenarios());
        assertThrows(UnsupportedOperationException.class, () -> expectation.getArtifacts().clear());
        assertThrows(UnsupportedOperationException.class, () -> expectation.getRequiredScenarios().clear());
    }

    @Test
    @DisplayName("解析拒绝报告总长度、单行长度和行数超限")
    void 解析拒绝文本规模超限() {
        assertThrows(
                AcceptanceReportValidationException.class,
                () -> AcceptanceReportV2Parser.parse(repeat('x', AcceptanceReportV2Parser.MAX_REPORT_CHARACTERS + 1)));
        String oversizedLine = AcceptanceReportV2Renderer.render(report())
                .replace("RUN_ID\trun-1", "RUN_ID\t" + repeat('x', AcceptanceReportV2Parser.MAX_LINE_CHARACTERS + 1));
        assertThrows(AcceptanceReportValidationException.class, () -> AcceptanceReportV2Parser.parse(oversizedLine));
        assertThrows(AcceptanceReportValidationException.class, () -> AcceptanceReportV2Parser.parse(tooManyLines()));
    }

    @Test
    @DisplayName("解析拒绝制品和场景数量超限")
    void 解析拒绝记录数量超限() {
        List<ReportArtifact> artifacts = new ArrayList<>();
        for (int index = 0; index <= AcceptanceReportV2Parser.MAX_ARTIFACTS; index++) {
            artifacts.add(new ReportArtifact("role-" + index, SERVER_HASH));
        }
        String artifactReport = AcceptanceReportV2Renderer.render(createReport(artifacts, Collections.singletonList(
                new ReportScenario("product-handshake", ScenarioStatus.PASS, 1L, "通过"))));
        assertThrows(AcceptanceReportValidationException.class, () -> AcceptanceReportV2Parser.parse(artifactReport));

        List<ReportScenario> scenarios = new ArrayList<>();
        for (int index = 0; index <= AcceptanceReportV2Parser.MAX_SCENARIOS; index++) {
            scenarios.add(new ReportScenario("scenario-" + index, ScenarioStatus.PASS, 1L, "通过"));
        }
        String scenarioReport = AcceptanceReportV2Renderer.render(createReport(
                Collections.singletonList(new ReportArtifact("server-runtime", SERVER_HASH)), scenarios));
        assertThrows(AcceptanceReportValidationException.class, () -> AcceptanceReportV2Parser.parse(scenarioReport));
    }

    private static String tooManyLines() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index <= AcceptanceReportV2Parser.MAX_REPORT_LINES; index++) {
            text.append("X\n");
        }
        return text.toString();
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private static AcceptanceReportV2 createReport(
            List<ReportArtifact> artifacts, List<ReportScenario> scenarios) {
        return AcceptanceReportV2.create(
                "run-1",
                "R1",
                1000L,
                new JavaRuntimeInfo(21, "C:/server/java.exe"),
                new JavaRuntimeInfo(21, "C:/client/java.exe"),
                artifacts,
                scenarios);
    }

    private static AcceptanceReportV2 report() {
        List<ReportArtifact> artifacts = Arrays.asList(
                new ReportArtifact("server-runtime", SERVER_HASH),
                new ReportArtifact("client-product", CLIENT_HASH));
        List<ReportScenario> scenarios = Arrays.asList(
                new ReportScenario("product-handshake", ScenarioStatus.PASS, 12L, "第一行\n第二行"),
                new ReportScenario("optional-capability", ScenarioStatus.SKIP, 0L, "未启用"));
        return AcceptanceReportV2.create(
                "run-1",
                "R1",
                1000L,
                new JavaRuntimeInfo(21, "C:/Java 21/bin/java.exe"),
                new JavaRuntimeInfo(21, "C:/Java 21/bin/javaw.exe"),
                artifacts,
                scenarios);
    }
}
