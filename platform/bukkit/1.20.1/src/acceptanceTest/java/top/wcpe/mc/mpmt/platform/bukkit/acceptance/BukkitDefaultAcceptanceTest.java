package top.wcpe.mc.mpmt.platform.bukkit.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReportMetadata;
import top.wcpe.mc.mpmt.acceptance.report.DefaultScenarioMatrix;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.sim.BukkitDefaultSimulation;

/** Bukkit 1.20.1 纯 JVM 默认轨清单与 acceptance v2 严格报告契约。 */
class BukkitDefaultAcceptanceTest {

    @Test
    @DisplayName("Bukkit 纯 JVM覆盖完整默认轨清单且全部通过")
    void 模拟服覆盖完整Default清单() throws Exception {
        List<ScenarioResult> results = runSimulation();

        assertEquals(DefaultScenarioMatrix.requiredFor("bukkit"), scenarioIds(results));
        assertTrue(results.stream().allMatch(result -> result.getStatus() == ScenarioStatus.PASS));
    }

    @Test
    @DisplayName("Bukkit acceptance v2 报告缺任一场景即拒绝")
    void 缺场景报告失败() throws Exception {
        List<ScenarioResult> complete = runSimulation();
        AcceptanceReportMetadata metadata = metadata();
        String accepted = AcceptanceReport.render(metadata, complete);
        assertTrue(AcceptanceReport.isAcceptedReport(accepted));

        List<ScenarioResult> incomplete = new ArrayList<>(complete);
        incomplete.remove(incomplete.size() - 1);
        assertFalse(AcceptanceReport.isAcceptedReport(AcceptanceReport.render(metadata, incomplete)));
    }

    @Test
    @DisplayName("兜底元数据在必填属性全缺失时不抛异常且仍是合规 acceptance v2")
    void 兜底元数据缺属性仍合规() {
        // 兜底分支可能在驱动异常、属性尚未注入时执行，因此绝不能再抛一次异常
        AcceptanceReportMetadata fallback = BukkitDefaultSimulation.failureMetadata("bukkit");

        assertEquals("bukkit", fallback.getPlatform());
        assertFalse(fallback.getScenarios().isEmpty(), "v2 要求场景清单非空");
    }

    @Test
    @DisplayName("兜底报告是 acceptance v2 且判定 RESULT FAIL，不再退化为 v1")
    void 兜底报告为v2失败报告() {
        AcceptanceReportMetadata fallback = BukkitDefaultSimulation.failureMetadata("bukkit");
        List<ScenarioResult> driverError =
                Collections.singletonList(
                        new ScenarioResult(
                                "framework",
                                "driver-error",
                                ScenarioStatus.ERROR,
                                0L,
                                "模拟驱动异常"));

        String report = AcceptanceReport.render(fallback, driverError);

        assertTrue(report.startsWith(AcceptanceReport.HEADER_V2), "兜底报告头必须是 acceptance v2");
        assertTrue(report.contains(AcceptanceReport.RESULT_FAIL), "兜底报告必须显式 RESULT FAIL");
        assertTrue(report.contains("driver-error"), "真实异常原因必须体现在报告里");
    }

    private static List<ScenarioResult> runSimulation() {
        return BukkitDefaultSimulation.run();
    }

    private static List<String> scenarioIds(List<ScenarioResult> results) {
        return results.stream()
                .map(result -> result.getSuite() + "/" + result.getId())
                .collect(Collectors.toList());
    }

    private static AcceptanceReportMetadata metadata() {
        return new AcceptanceReportMetadata(
                "test-commit",
                "0.1.0",
                "bukkit",
                "1.20.1",
                "Paper 1.20.1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                DefaultScenarioMatrix.requiredFor("bukkit"));
    }
}
