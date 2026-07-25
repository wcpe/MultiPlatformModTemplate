package top.wcpe.mc.mpmt.acceptance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.RunStepPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepStatus;

/** 验收客户端排程协调：seq 对账、就绪门闩、超时、收尾唤醒（ADR-0014）。 */
class AcceptanceClientTest {

    @Test
    @DisplayName("构造入参为空即拒")
    void 入参校验() {
        assertThrows(NullPointerException.class, () -> new AcceptanceClient(null));
    }

    @Test
    @DisplayName("就绪门闩：未就绪超时 false，收到 ClientReady 后放行")
    void 就绪门闩() {
        AcceptanceClient client = new AcceptanceClient(bytes -> {});
        assertFalse(client.awaitReady(30));
        client.onClientReady(ready());
        assertTrue(client.awaitReady(30));
        assertEquals(AcceptanceControlCodec.PROTOCOL_VERSION, client.clientProtocolVersion());
        assertEquals(17, client.clientJava().getMajor());
        assertEquals("test-java", client.clientJava().getExecutable());
    }

    @Test
    @DisplayName("runStep 按 seq 匹配回报并返回结果；seq 递增")
    void 按seq匹配() throws Exception {
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        AcceptanceClient client = new AcceptanceClient(sent::add);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<StepResultPacket> first = exec.submit(() -> client.runStep("sc", "st1", "{}", 2000));
            RunStepPacket run1 = awaitRunStep(sent, 0);
            assertEquals("sc", run1.getScenarioId());
            assertEquals("st1", run1.getStepId());
            StepResultPacket reply1 =
                    new StepResultPacket("sc", "st1", run1.getSeq(), StepStatus.OK, "{}", "done");
            client.onStepResult(reply1);
            assertEquals(reply1, first.get(2, TimeUnit.SECONDS));

            // 第二步 seq 应递增
            Future<StepResultPacket> second = exec.submit(() -> client.runStep("sc", "st2", "{}", 2000));
            RunStepPacket run2 = awaitRunStep(sent, 1);
            assertTrue(run2.getSeq() > run1.getSeq(), "seq 应递增");
            client.onStepResult(new StepResultPacket("sc", "st2", run2.getSeq(), StepStatus.OK, "{}", ""));
            second.get(2, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("无客户端回报：runStep 超时抛 AcceptanceTimeoutException")
    void 步骤超时() {
        AcceptanceClient client = new AcceptanceClient(bytes -> {});
        assertThrows(
                AcceptanceTimeoutException.class, () -> client.runStep("sc", "st", "{}", 40));
    }

    @Test
    @DisplayName("failAllPending：唤醒挂起的 runStep（异常完成）")
    void 收尾唤醒() throws Exception {
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        AcceptanceClient client = new AcceptanceClient(sent::add);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<StepResultPacket> pending = exec.submit(() -> client.runStep("sc", "st", "{}", 5000));
            awaitRunStep(sent, 0); // 确保已注册挂起
            client.failAllPending("通道收尾");
            ExecutionException ex =
                    assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof AcceptanceTimeoutException);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("无匹配 seq 的回报被忽略，不报错")
    void 未匹配seq忽略() {
        AcceptanceClient client = new AcceptanceClient(bytes -> {});
        assertDoesNotThrow(
                () -> client.onStepResult(
                        new StepResultPacket("s", "st", 999, StepStatus.OK, "{}", "")));
    }

    @Test
    @DisplayName("有挂起步骤的意外断线必须失败")
    void 意外断线失败挂起步骤() throws Exception {
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        AcceptanceClient client = new AcceptanceClient(sent::add);
        client.onClientReady(ready());
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<StepResultPacket> pending = exec.submit(() -> client.runStep("sc", "st", "{}", 5000));
            awaitRunStep(sent, 0);

            client.onDisconnected("客户端意外断线");

            ExecutionException ex = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof AcceptanceTimeoutException);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("预先声明的重连保留挂起步骤并隔离旧 generation 回报")
    void 预期重连隔离旧generation() throws Exception {
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        AcceptanceClient client = new AcceptanceClient(sent::add);
        client.onClientReady(ready());
        long oldGeneration = client.generation();
        client.expectReconnect();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<StepResultPacket> pending = exec.submit(() -> client.runStep("reconnect", "verify", "{}", 5000));
            RunStepPacket run = awaitRunStep(sent, 0);

            client.onDisconnected("预期断线");
            long newGeneration = client.generation();
            assertTrue(newGeneration > oldGeneration);
            assertFalse(pending.isDone(), "预期重连不应失败挂起步骤");

            StepResultPacket result =
                    new StepResultPacket("reconnect", "verify", run.getSeq(), StepStatus.OK, "{}", "ok");
            client.onStepResult(oldGeneration, result);
            assertFalse(pending.isDone(), "旧 generation 的迟到消息不得完成新 generation 步骤");

            client.onClientReady(newGeneration, ready());
            client.onStepResult(newGeneration, result);
            assertEquals(result, pending.get(2, TimeUnit.SECONDS));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("协议版本或 Java 身份非法时拒绝就绪")
    void 非法就绪被拒() {
        AcceptanceClient client = new AcceptanceClient(bytes -> {});
        assertThrows(
                IllegalArgumentException.class,
                () -> client.onClientReady(new ClientReadyPacket(1, 17, "java")));
        assertThrows(
                IllegalArgumentException.class,
                () -> client.onClientReady(
                        new ClientReadyPacket(AcceptanceControlCodec.PROTOCOL_VERSION, 0, "java")));
        assertThrows(
                IllegalArgumentException.class,
                () -> client.onClientReady(
                        new ClientReadyPacket(AcceptanceControlCodec.PROTOCOL_VERSION, 17, "")));
        assertFalse(client.awaitReady(20));
    }

    private static ClientReadyPacket ready() {
        return new ClientReadyPacket(AcceptanceControlCodec.PROTOCOL_VERSION, 17, "test-java");
    }

    /** 轮询等待第 index 个 RunStep 发出并解码（最多约 1 秒）。 */
    private static RunStepPacket awaitRunStep(List<byte[]> sent, int index) throws InterruptedException {
        for (int i = 0; i < 200 && sent.size() <= index; i++) {
            Thread.sleep(5);
        }
        if (sent.size() <= index) {
            throw new AssertionError("等待 RunStep 发出超时，index=" + index);
        }
        return (RunStepPacket) AcceptanceControlCodec.decode(sent.get(index));
    }
}
