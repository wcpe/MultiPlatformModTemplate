package top.wcpe.mc.mpmt.acceptance.report;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** v2 报告严格 validator 的拒绝条件。 */
class AcceptanceReportV2ValidatorTest {

    private static final long START_EPOCH_MS = 1000L;
    private static final String SERVER_RUNTIME_HASH = hash('1');
    private static final String SERVER_PRODUCT_HASH = hash('2');
    private static final String SERVER_ACCEPTANCE_HASH = hash('3');
    private static final String CLIENT_PRODUCT_HASH = hash('4');
    private static final String CLIENT_ACCEPTANCE_HASH = hash('5');

    @Test
    @DisplayName("全部预期匹配且 required 场景恰好一次 PASS 时通过")
    void 严格校验通过() {
        assertDoesNotThrow(() -> AcceptanceReportV2Validator.validate(validText(), START_EPOCH_MS, expectation()));
    }

    @Test
    @DisplayName("非 required 场景 SKIP 保持兼容并允许通过")
    void 非required的skip允许通过() {
        List<ReportScenario> scenarios = new ArrayList<>(requiredPassScenarios());
        scenarios.add(new ReportScenario("optional-capability", ScenarioStatus.SKIP, 0L, "未启用"));

        assertDoesNotThrow(() -> AcceptanceReportV2Validator.validate(render(scenarios), START_EPOCH_MS, expectation()));
    }

    @Test
    @DisplayName("R5 缺少融合服专属 required 场景时拒绝")
    void r5缺少专属required拒绝() {
        assertRejected(render("R5", requiredPassScenarios()), expectation("R5"));
    }

    @Test
    @DisplayName("R6 缺少调度专属 required 场景时拒绝")
    void r6缺少专属required拒绝() {
        assertRejected(render("R6", requiredPassScenarios()), expectation("R6"));
    }

    @Test
    @DisplayName("R7 在构造预期时使用公共三场景")
    void r7构造预期() {
        assertDoesNotThrow(() -> expectation("R7"));
    }

    @Test
    @DisplayName("空报告及伪造的空报告 RESULT PASS 均拒绝")
    void 空报告拒绝() {
        String empty = render(Collections.emptyList());
        assertRejected(empty);
        assertRejected(empty.replace("RESULT FAIL", "RESULT PASS"));
    }

    @Test
    @DisplayName("拒绝旧报告和早于本轮启动的报告文件")
    void 拒绝旧报告与旧文件() {
        assertThrows(
                AcceptanceReportValidationException.class,
                () -> AcceptanceReportV2Validator.validate(
                        validText().replace("SERVER-GAMETEST-REPORT v2", "SERVER-GAMETEST-REPORT v1"),
                        START_EPOCH_MS,
                        expectation()));
        assertThrows(
                AcceptanceReportValidationException.class,
                () -> AcceptanceReportV2Validator.validate(validText(), START_EPOCH_MS - 1L, expectation()));
    }

    @Test
    @DisplayName("拒绝缺失或重复的元数据、制品和场景")
    void 拒绝缺失或重复记录() {
        String valid = validText();
        assertRejected(valid.replace("RUN_ID\trun-1\n", ""));
        assertRejected(valid.replace("MATRIX\tR1\n", "MATRIX\tR1\nMATRIX\tR1\n"));
        assertRejected(valid.replace(artifactLine("server-runtime", SERVER_RUNTIME_HASH), ""));
        assertRejected(valid.replace(
                artifactLine("server-runtime", SERVER_RUNTIME_HASH),
                artifactLine("server-runtime", SERVER_RUNTIME_HASH)
                        + artifactLine("server-runtime", SERVER_RUNTIME_HASH)));
        assertRejected(valid.replace(scenarioLine("client-hud"), ""));
        assertRejected(valid.replace(
                scenarioLine("client-hud"), scenarioLine("client-hud") + scenarioLine("client-hud")));
    }

    @Test
    @DisplayName("required 场景出现 SKIP、FAIL 或 ERROR 均拒绝")
    void 拒绝required非pass() {
        for (ScenarioStatus status : Arrays.asList(ScenarioStatus.SKIP, ScenarioStatus.FAIL, ScenarioStatus.ERROR)) {
            List<ReportScenario> scenarios = requiredPassScenarios();
            scenarios.set(0, new ReportScenario("product-handshake", status, 1L, "状态异常"));
            assertRejected(render(scenarios));
        }
    }

    @Test
    @DisplayName("任意非 required 场景出现 FAIL 或 ERROR 均拒绝")
    void 拒绝任意失败错误() {
        for (ScenarioStatus status : Arrays.asList(ScenarioStatus.FAIL, ScenarioStatus.ERROR)) {
            List<ReportScenario> scenarios = requiredPassScenarios();
            scenarios.add(new ReportScenario("optional-capability", status, 1L, "状态异常"));
            assertRejected(render(scenarios));
        }
    }

    @Test
    @DisplayName("拒绝 runId、matrix、启动时间、JDK 和制品哈希不匹配")
    void 拒绝预期不匹配() {
        String valid = validText();
        assertRejected(valid.replace("RUN_ID\trun-1", "RUN_ID\trun-2"));
        assertRejected(valid.replace("MATRIX\tR1", "MATRIX\tR2"));
        assertRejected(valid.replace("START_EPOCH_MS\t1000", "START_EPOCH_MS\t1001"));
        assertRejected(valid.replace("SERVER_JAVA\t21", "SERVER_JAVA\t17"));
        assertRejected(valid.replace("C:/server/java.exe", "C:/other/java.exe"));
        assertRejected(valid.replace("CLIENT_JAVA\t21", "CLIENT_JAVA\t17"));
        assertRejected(valid.replace("C:/client/java.exe", "C:/other-client/java.exe"));
        assertRejected(valid.replace(SERVER_PRODUCT_HASH, hash('a')));
    }

    @Test
    @DisplayName("拒绝未预期制品、TOTAL 不一致和末行非 PASS")
    void 拒绝汇总与末行异常() {
        String valid = validText();
        assertRejected(valid.replace(
                "SCENARIO\tproduct-handshake",
                artifactLine("client-runtime", hash('6')) + "SCENARIO\tproduct-handshake"));
        assertRejected(valid.replace("TOTAL 3 PASS 3 FAIL 0 ERROR 0 SKIP 0", "TOTAL 4 PASS 3 FAIL 0 ERROR 0 SKIP 1"));
        assertRejected(valid.replace("RESULT PASS\n", "RESULT FAIL\n"));
    }

    private static void assertRejected(String text) {
        assertRejected(text, expectation());
    }

    private static void assertRejected(String text, AcceptanceReportV2Expectation expected) {
        assertThrows(
                AcceptanceReportValidationException.class,
                () -> AcceptanceReportV2Validator.validate(text, START_EPOCH_MS, expected));
    }

    private static String validText() {
        return render(requiredPassScenarios());
    }

    private static String render(List<ReportScenario> scenarios) {
        return render("R1", scenarios);
    }

    private static String render(String matrix, List<ReportScenario> scenarios) {
        AcceptanceReportV2 report = AcceptanceReportV2.create(
                "run-1",
                matrix,
                START_EPOCH_MS,
                new JavaRuntimeInfo(21, "C:/server/java.exe"),
                new JavaRuntimeInfo(21, "C:/client/java.exe"),
                artifacts(),
                scenarios);
        return AcceptanceReportV2Renderer.render(report);
    }

    private static AcceptanceReportV2Expectation expectation() {
        return expectation("R1");
    }

    private static AcceptanceReportV2Expectation expectation(String matrix) {
        return new AcceptanceReportV2Expectation(
                "run-1",
                matrix,
                START_EPOCH_MS,
                new JavaRuntimeInfo(21, "C:/server/java.exe"),
                new JavaRuntimeInfo(21, "C:/client/java.exe"),
                artifacts());
    }

    private static List<ReportArtifact> artifacts() {
        return Arrays.asList(
                new ReportArtifact("server-runtime", SERVER_RUNTIME_HASH),
                new ReportArtifact("server-product", SERVER_PRODUCT_HASH),
                new ReportArtifact("server-acceptance", SERVER_ACCEPTANCE_HASH),
                new ReportArtifact("client-product", CLIENT_PRODUCT_HASH),
                new ReportArtifact("client-acceptance", CLIENT_ACCEPTANCE_HASH));
    }

    private static List<ReportScenario> requiredPassScenarios() {
        return new ArrayList<>(Arrays.asList(
                new ReportScenario("product-handshake", ScenarioStatus.PASS, 1L, "通过"),
                new ReportScenario("product-roundtrip", ScenarioStatus.PASS, 2L, "通过"),
                new ReportScenario("client-hud", ScenarioStatus.PASS, 3L, "通过")));
    }

    private static String artifactLine(String role, String hash) {
        return "ARTIFACT\t" + role + "\t" + hash + "\n";
    }

    private static String scenarioLine(String id) {
        return "SCENARIO\t" + id + "\tPASS\t" + scenarioDuration(id) + "\t通过\n";
    }

    private static long scenarioDuration(String id) {
        if ("product-handshake".equals(id)) {
            return 1L;
        }
        if ("product-roundtrip".equals(id)) {
            return 2L;
        }
        return 3L;
    }

    private static String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
