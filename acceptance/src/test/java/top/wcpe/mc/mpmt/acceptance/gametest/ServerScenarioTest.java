package top.wcpe.mc.mpmt.acceptance.gametest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.RunStepPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepStatus;

/** 验收场景：客户端排程委托与状态→断言映射（ADR-0014）。 */
class ServerScenarioTest {

    /** 测试用场景：暴露受保护的排程方法（同包子类可访问）。 */
    private static final class DemoScenario extends ServerScenario {
        @Override
        public String suite() {
            return "acceptance";
        }

        @Override
        public String id() {
            return "demo";
        }

        @Override
        public void run(ServerGameTestContext context) {
            // 测试直接调下面的包装方法，不走 run
        }

        StepResultPacket step(String stepId, long timeoutMs) {
            return runClientStep(stepId, "{}", timeoutMs);
        }

        void ready(long timeoutMs) {
            awaitClientReady(timeoutMs);
        }
    }

    @Test
    @DisplayName("未绑定 AcceptanceClient 即失败快")
    void 未绑定即拒() {
        DemoScenario scenario = new DemoScenario();
        assertThrows(IllegalStateException.class, () -> scenario.step("x", 100));
    }

    @Test
    @DisplayName("runClientStep：OK 回报返回，scenarioId 为场景 id")
    void 步骤通过() throws Exception {
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        AcceptanceClient client = new AcceptanceClient(sent::add);
        DemoScenario scenario = new DemoScenario();
        scenario.bindClient(client);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<StepResultPacket> future = exec.submit(() -> scenario.step("verify", 2000));
            RunStepPacket run = awaitRunStep(sent);
            assertEquals("demo", run.getScenarioId());
            assertEquals("verify", run.getStepId());
            client.onStepResult(new StepResultPacket("demo", "verify", run.getSeq(), StepStatus.OK, "{}", "ok"));
            assertEquals(StepStatus.OK, future.get(2, TimeUnit.SECONDS).getStatus());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("runClientStep：非 OK 回报抛断言错")
    void 步骤失败() throws Exception {
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        AcceptanceClient client = new AcceptanceClient(sent::add);
        DemoScenario scenario = new DemoScenario();
        scenario.bindClient(client);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<StepResultPacket> future = exec.submit(() -> scenario.step("verify", 2000));
            RunStepPacket run = awaitRunStep(sent);
            client.onStepResult(
                    new StepResultPacket("demo", "verify", run.getSeq(), StepStatus.FAIL, "{}", "客户端失败"));
            ExecutionException ex =
                    assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof ServerGameTestAssertionError);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("awaitClientReady 超时抛断言错")
    void 就绪超时() {
        AcceptanceClient client = new AcceptanceClient(bytes -> { });
        DemoScenario scenario = new DemoScenario();
        scenario.bindClient(client);
        assertThrows(ServerGameTestAssertionError.class, () -> scenario.ready(40));
    }

    private static RunStepPacket awaitRunStep(List<byte[]> sent) throws InterruptedException {
        for (int i = 0; i < 200 && sent.isEmpty(); i++) {
            Thread.sleep(5);
        }
        if (sent.isEmpty()) {
            throw new AssertionError("等待 RunStep 发出超时");
        }
        return (RunStepPacket) AcceptanceControlCodec.decode(sent.get(0));
    }
}
