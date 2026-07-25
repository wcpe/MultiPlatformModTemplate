package top.wcpe.mc.mpmt.acceptance;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.RunStepPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.acceptance.report.JavaRuntimeInfo;

/**
 * 验收客户端排程协调：按 seq 对账，并用 generation 隔离重连前后的控制消息。
 *
 * <p>普通断线会立即失败全部挂起步骤。只有场景事先调用 {@link #expectReconnect()}，断线后挂起步骤才迁移到
 * 下一 generation；旧 generation 的迟到回报始终忽略。
 */
public final class AcceptanceClient {

    private final Consumer<byte[]> sendRaw;
    private final AtomicInteger seqGen = new AtomicInteger();
    private final Map<Integer, PendingStep> pending = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();

    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile long generation = 1L;
    private volatile int clientProtocolVersion;
    private volatile JavaRuntimeInfo clientJava;
    private boolean reconnectExpected;

    public AcceptanceClient(Consumer<byte[]> sendRaw) {
        this.sendRaw = Objects.requireNonNull(sendRaw, "sendRaw 不能为空");
    }

    /** 等当前 generation 客户端连入并通道就绪；true=就绪，false=超时。 */
    public boolean awaitReady(long timeoutMs) {
        CountDownLatch latch = readyLatch;
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcceptanceTimeoutException("等客户端就绪被中断");
        }
    }

    /** 当前控制连接 generation，首次为 1，预期重连断开后递增。 */
    public long generation() {
        return generation;
    }

    /** 客户端上报的控制协议版本（就绪后有效）。 */
    public int clientProtocolVersion() {
        return clientProtocolVersion;
    }

    /** 客户端上报的实际 Java 运行身份；未合法就绪时为空。 */
    public JavaRuntimeInfo clientJava() {
        return clientJava;
    }

    /** 声明下一次断线属于当前场景的预期重连；必须在断线发生前调用。 */
    public void expectReconnect() {
        synchronized (lifecycleLock) {
            if (reconnectExpected) {
                throw new IllegalStateException("已经声明预期重连");
            }
            reconnectExpected = true;
        }
    }

    /** 给客户端排程一步验证并阻塞等回报；超时 / 中断 / 断线抛 {@link AcceptanceTimeoutException}。 */
    public StepResultPacket runStep(String scenarioId, String stepId, String paramsJson, long timeoutMs) {
        int seq = seqGen.incrementAndGet();
        PendingStep step;
        synchronized (lifecycleLock) {
            step = new PendingStep(generation, scenarioId, stepId);
            pending.put(seq, step);
        }
        try {
            sendRaw.accept(
                    AcceptanceControlCodec.encode(new RunStepPacket(scenarioId, stepId, paramsJson, seq)));
            return awaitStep(step, scenarioId, stepId, timeoutMs);
        } finally {
            pending.remove(seq, step);
        }
    }

    private static StepResultPacket awaitStep(
            PendingStep step, String scenarioId, String stepId, long timeoutMs) {
        try {
            return step.future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AcceptanceTimeoutException(
                    "客户端步骤超时：scenario=" + scenarioId + " step=" + stepId + " timeoutMs=" + timeoutMs);
        } catch (ExecutionException e) {
            String cause = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            throw new AcceptanceTimeoutException("客户端步骤异常完成：" + cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcceptanceTimeoutException("客户端步骤等待被中断");
        }
    }

    /** 接收侧：当前 generation 客户端就绪。 */
    public void onClientReady(ClientReadyPacket packet) {
        onClientReady(generation, packet);
    }

    /** 接收侧：指定 generation 客户端就绪；活跃连接不得跳代。 */
    public void onClientReady(long messageGeneration, ClientReadyPacket packet) {
        Objects.requireNonNull(packet, "packet 不能为空");
        if (packet.getProtocolVersion() != AcceptanceControlCodec.PROTOCOL_VERSION) {
            throw new IllegalArgumentException(
                    "客户端控制协议版本不匹配：" + packet.getProtocolVersion());
        }
        if (packet.getJavaMajor() <= 0 || packet.getJavaExecutable() == null || packet.getJavaExecutable().isEmpty()) {
            throw new IllegalArgumentException("客户端未上报有效 Java 运行身份");
        }
        JavaRuntimeInfo reportedJava =
                new JavaRuntimeInfo(packet.getJavaMajor(), packet.getJavaExecutable());
        synchronized (lifecycleLock) {
            if (messageGeneration != generation) {
                throw new IllegalStateException(
                        "客户端 generation 不匹配：expected=" + generation + " actual=" + messageGeneration);
            }
            clientProtocolVersion = packet.getProtocolVersion();
            clientJava = reportedJava;
            readyLatch.countDown();
        }
    }

    /** 接收侧：当前 generation 客户端回报一步结果。 */
    public void onStepResult(StepResultPacket packet) {
        onStepResult(generation, packet);
    }

    /** 接收侧：指定 generation 客户端回报一步结果；旧代或未知 seq 回报忽略。 */
    public void onStepResult(long messageGeneration, StepResultPacket packet) {
        Objects.requireNonNull(packet, "packet 不能为空");
        PendingStep step = pending.get(packet.getSeq());
        if (step == null || step.generation != messageGeneration) {
            return;
        }
        if (!step.matches(packet)) {
            step.future.completeExceptionally(new AcceptanceTimeoutException(
                    "客户端步骤回报错配：expected=" + step.scenarioId + "/" + step.stepId
                            + " actual=" + packet.getScenarioId() + "/" + packet.getStepId()));
            return;
        }
        step.future.complete(packet);
    }

    /** 连接断开：意外断线失败挂起步骤；预期重连则迁移至下一 generation。 */
    public void onDisconnected(String reason) {
        boolean recover;
        synchronized (lifecycleLock) {
            recover = reconnectExpected;
            reconnectExpected = false;
            long nextGeneration = generation + 1L;
            generation = nextGeneration;
            readyLatch = new CountDownLatch(1);
            if (recover) {
                for (PendingStep step : pending.values()) {
                    step.generation = nextGeneration;
                }
            }
        }
        if (!recover) {
            failAllPending(reason);
        }
    }

    /** 收尾：异常完成所有挂起步骤，唤醒阻塞的 {@link #runStep}。 */
    public void failAllPending(String reason) {
        for (PendingStep step : pending.values()) {
            step.future.completeExceptionally(new AcceptanceTimeoutException(reason));
        }
    }

    private static final class PendingStep {

        private volatile long generation;
        private final String scenarioId;
        private final String stepId;
        private final CompletableFuture<StepResultPacket> future = new CompletableFuture<>();

        private PendingStep(long generation, String scenarioId, String stepId) {
            this.generation = generation;
            this.scenarioId = scenarioId;
            this.stepId = stepId;
        }

        private boolean matches(StepResultPacket packet) {
            return scenarioId.equals(packet.getScenarioId()) && stepId.equals(packet.getStepId());
        }
    }
}
