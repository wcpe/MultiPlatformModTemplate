package top.wcpe.mc.mpmt.acceptance.gametest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;

/** 执行器把用例结果归类为 PASS/SKIP/FAIL/ERROR 并隔离用例间失败（ADR-0014）。 */
class ServerGameTestRunnerTest {

    private static ServerGameTest test(String id, Consumer<ServerGameTestContext> body) {
        return new ServerGameTest() {
            @Override
            public String suite() {
                return "acceptance";
            }

            @Override
            public String id() {
                return id;
            }

            @Override
            public void run(ServerGameTestContext context) {
                body.accept(context);
            }
        };
    }

    @Test
    @DisplayName("四态归类正确、用例间隔离、聚合判 RESULT FAIL")
    void 归类与隔离() {
        List<ServerGameTest> tests =
                Arrays.asList(
                        test("p", ctx -> { }),
                        test("f", ctx -> ctx.assertTrue(false, "断言失败")),
                        test("s", ctx -> ctx.skip("跳过原因")),
                        test("e", ctx -> {
                            throw new IllegalStateException("炸了");
                        }));
        Function<ServerGameTest, ServerGameTestContext> factory = t -> new FakeServerGameTestContext();

        List<ScenarioResult> results = ServerGameTestRunner.runAll(tests, factory);

        // 一个用例 ERROR 不中断后续：四个都跑出结果
        assertEquals(4, results.size());
        java.util.Map<String, ScenarioResult> byId =
                results.stream().collect(Collectors.toMap(ScenarioResult::getId, r -> r));
        assertEquals(ScenarioStatus.PASS, byId.get("p").getStatus());
        assertEquals(ScenarioStatus.FAIL, byId.get("f").getStatus());
        assertEquals("断言失败", byId.get("f").getMessage());
        assertEquals(ScenarioStatus.SKIP, byId.get("s").getStatus());
        assertEquals("跳过原因", byId.get("s").getMessage());
        assertEquals(ScenarioStatus.ERROR, byId.get("e").getStatus());
        assertTrue(byId.get("e").getMessage().contains("IllegalStateException"), byId.get("e").getMessage());
        assertTrue(byId.get("e").getMessage().contains("炸了"), byId.get("e").getMessage());

        // 含 FAIL/ERROR → 整体不通过
        assertFalse(AcceptanceReport.isPass(results));
    }

    @Test
    @DisplayName("全通过聚合判 RESULT PASS")
    void 全通过() {
        List<ServerGameTest> tests = Arrays.asList(test("a", ctx -> { }), test("b", ctx -> { }));
        List<ScenarioResult> results =
                ServerGameTestRunner.runAll(tests, t -> new FakeServerGameTestContext());
        assertTrue(AcceptanceReport.isPass(results));
    }
}
