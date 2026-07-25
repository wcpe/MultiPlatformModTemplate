package top.wcpe.mc.mpmt.platform.fabric.gametest.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTest;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.platform.fabric.gametest.sim.SimScenarioCatalog;

/**
 * Fabric realserver P1 场景目录：13 项进程内回环 + {@code real-round-trip} 客户端联调。
 * 顺序必须与 {@link P1ScenarioMatrix#requiredFor(String)} 的 fabric 清单一致。
 */
public final class RealServerScenarioCatalog {

    private RealServerScenarioCatalog() {
        // 工具类不实例化
    }

    /** 完整 REAL_REQUIRED 测试列表（14 项）。 */
    public static List<ServerGameTest> all() {
        List<ServerGameTest> tests = new ArrayList<>(SimScenarioCatalog.loopbackCore());
        tests.add(new RealRoundTripServerScenario());
        return Collections.unmodifiableList(tests);
    }

    /** suite/id 形式的场景清单，供 v2 META scenarios 与矩阵对账。 */
    public static List<String> scenarioIds() {
        List<String> ids = new ArrayList<>();
        for (ServerGameTest test : all()) {
            ids.add(test.suite() + '/' + test.id());
        }
        return Collections.unmodifiableList(ids);
    }

    /** 与矩阵对账；不一致则失败快，避免静默漏验。 */
    public static void assertMatchesMatrix() {
        List<String> required = P1ScenarioMatrix.requiredFor("fabric");
        if (!required.equals(scenarioIds())) {
            throw new IllegalStateException(
                    "Fabric realserver 场景目录与 P1 矩阵不一致：catalog="
                            + scenarioIds()
                            + " matrix="
                            + required);
        }
    }
}
