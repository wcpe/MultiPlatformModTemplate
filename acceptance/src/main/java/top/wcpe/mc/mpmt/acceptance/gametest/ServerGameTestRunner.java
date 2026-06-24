package top.wcpe.mc.mpmt.acceptance.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;

/**
 * 服务端 GameTest 执行器（ADR-0014，平台无关编排）：顺序执行用例，把每个用例的结果归类为
 * PASS / SKIP / FAIL / ERROR 并附耗时，聚合为 {@link ScenarioResult} 列表（供 {@code AcceptanceReport} 渲染权威报告）。
 *
 * <p>用例间相互隔离：单个用例抛出的任何异常都被记为 ERROR 并继续后续用例，<b>不让一个用例的失败中断整套</b>。
 * 起服 / 看门狗 / 写报告 / halt 等进程编排由平台引导层负责，不在本类。
 */
public final class ServerGameTestRunner {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private ServerGameTestRunner() {
        // 工具类不实例化
    }

    /**
     * 顺序执行全部用例并聚合结果。
     *
     * @param tests          已排序的用例列表
     * @param contextFactory 为每个用例创建其执行上下文（平台提供）
     */
    public static List<ScenarioResult> runAll(
            List<ServerGameTest> tests, Function<ServerGameTest, ServerGameTestContext> contextFactory) {
        List<ScenarioResult> results = new ArrayList<>();
        for (ServerGameTest test : tests) {
            results.add(runOne(test, contextFactory));
        }
        return results;
    }

    private static ScenarioResult runOne(
            ServerGameTest test, Function<ServerGameTest, ServerGameTestContext> contextFactory) {
        long start = System.nanoTime();
        ScenarioStatus status;
        String message;
        try {
            test.run(contextFactory.apply(test));
            status = ScenarioStatus.PASS;
            message = "";
        } catch (ServerGameTestSkipException e) {
            status = ScenarioStatus.SKIP;
            message = nullToEmpty(e.getMessage());
        } catch (ServerGameTestAssertionError e) {
            status = ScenarioStatus.FAIL;
            message = nullToEmpty(e.getMessage());
        } catch (Throwable t) {
            // 用例隔离：任何其他异常记为 ERROR 并继续（不吞没——写入报告诊断）
            if (t instanceof InterruptedException) {
                // 归类记 ERROR，但补回中断标志，勿吞掉看门狗 / 收尾对驱动线程的中断意图
                Thread.currentThread().interrupt();
            }
            status = ScenarioStatus.ERROR;
            message = t.getClass().getSimpleName() + ": " + nullToEmpty(t.getMessage());
        }
        long durationMs = (System.nanoTime() - start) / NANOS_PER_MILLI;
        return new ScenarioResult(test.suite(), test.id(), status, durationMs, message);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
