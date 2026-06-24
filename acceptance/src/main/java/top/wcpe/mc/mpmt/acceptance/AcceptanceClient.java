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

/**
 * 验收客户端排程协调（ADR-0014，平台无关）：服务端场景经它给程序化客户端排程一步验证并阻塞等回报。
 *
 * <p><b>seq 对账</b>：每步递增 seq + 注册 {@link CompletableFuture}，发 {@link RunStepPacket}；客户端回
 * {@link StepResultPacket} 时按 seq 完成对应 future、唤醒等待。<b>就绪门闩</b>：{@link CountDownLatch} 等
 * {@link ClientReadyPacket}。
 *
 * <p><b>线程模型</b>：{@code runStep} / {@code awaitReady} 在服务端驱动线程阻塞调用；{@code onClientReady} /
 * {@code onStepResult} 由通道接收侧（网络线程）调用以唤醒。{@code pending} 为并发安全映射。
 */
public final class AcceptanceClient {

    private final Consumer<byte[]> sendRaw;
    private final AtomicInteger seqGen = new AtomicInteger();
    private final Map<Integer, CompletableFuture<StepResultPacket>> pending = new ConcurrentHashMap<>();
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    private volatile int clientProtocolVersion;

    public AcceptanceClient(Consumer<byte[]> sendRaw) {
        this.sendRaw = Objects.requireNonNull(sendRaw, "sendRaw 不能为空");
    }

    /** 等客户端连入并通道就绪；true=就绪，false=超时。 */
    public boolean awaitReady(long timeoutMs) {
        try {
            return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcceptanceTimeoutException("等客户端就绪被中断");
        }
    }

    /** 客户端上报的控制协议版本（就绪后有效）。 */
    public int clientProtocolVersion() {
        return clientProtocolVersion;
    }

    /** 给客户端排程一步验证并阻塞等回报；超时 / 中断 / 收尾抛 {@link AcceptanceTimeoutException}。 */
    public StepResultPacket runStep(String scenarioId, String stepId, String paramsJson, long timeoutMs) {
        int seq = seqGen.incrementAndGet();
        CompletableFuture<StepResultPacket> future = new CompletableFuture<>();
        pending.put(seq, future);
        try {
            sendRaw.accept(
                    AcceptanceControlCodec.encode(new RunStepPacket(scenarioId, stepId, paramsJson, seq)));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AcceptanceTimeoutException(
                    "客户端步骤超时：scenario=" + scenarioId + " step=" + stepId + " timeoutMs=" + timeoutMs);
        } catch (ExecutionException e) {
            String cause = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            throw new AcceptanceTimeoutException("客户端步骤异常完成：" + cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcceptanceTimeoutException("客户端步骤等待被中断");
        } finally {
            pending.remove(seq);
        }
    }

    /** 接收侧：客户端就绪。 */
    public void onClientReady(ClientReadyPacket packet) {
        this.clientProtocolVersion = packet.getProtocolVersion();
        readyLatch.countDown();
    }

    /** 接收侧：客户端回报一步结果（无匹配 seq 的回报忽略，容错旧包 / 错接）。 */
    public void onStepResult(StepResultPacket packet) {
        CompletableFuture<StepResultPacket> future = pending.get(packet.getSeq());
        if (future != null) {
            future.complete(packet);
        }
    }

    /** 连接断开 / 收尾：异常完成所有挂起步骤，唤醒阻塞的 {@link #runStep}。 */
    public void failAllPending(String reason) {
        for (CompletableFuture<StepResultPacket> future : pending.values()) {
            future.completeExceptionally(new AcceptanceTimeoutException(reason));
        }
    }
}
