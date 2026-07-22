package top.wcpe.mc.mpmt.platform.sponge.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReportMetadata;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;

/** Sponge 1.20.1 纯 JVM P1 清单与 acceptance v2 严格报告契约。 */
class SpongeP1AcceptanceTest {

    @Test
    @DisplayName("Sponge 纯 JVM覆盖完整 P1 清单且全部通过")
    void 模拟服覆盖完整P1清单() throws Exception {
        List<ScenarioResult> results = runSimulation();

        assertEquals(P1ScenarioMatrix.requiredFor("sponge"), scenarioIds(results));
        assertTrue(results.stream().allMatch(result -> result.getStatus() == ScenarioStatus.PASS));
    }

    @Test
    @DisplayName("Sponge acceptance v2 报告缺任一场景即拒绝")
    void 缺场景报告失败() throws Exception {
        List<ScenarioResult> complete = runSimulation();
        AcceptanceReportMetadata metadata = metadata();
        String accepted = AcceptanceReport.render(metadata, complete);
        assertTrue(AcceptanceReport.isAcceptedReport(accepted));

        List<ScenarioResult> incomplete = new ArrayList<>(complete);
        incomplete.remove(incomplete.size() - 1);
        assertFalse(AcceptanceReport.isAcceptedReport(AcceptanceReport.render(metadata, incomplete)));
    }

    private static List<ScenarioResult> runSimulation() {
        return SpongeP1Simulation.run();
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
                "sponge",
                "1.20.1",
                "Sponge RC1365",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                P1ScenarioMatrix.requiredFor("sponge"));
    }
}
