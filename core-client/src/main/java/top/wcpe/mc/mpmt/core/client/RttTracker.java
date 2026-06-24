package top.wcpe.mc.mpmt.core.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 心跳 RTT 追踪（客户端，线程安全，FR-28）：记录 Ping 发送时刻，收到对应 Pong 计算 RTT，超时未回判为疑似丢失。
 *
 * <p>纯逻辑——不发包、不调度；周期发 Ping 与"疑似丢失 → 触发重连重同步"由上层（含 SchedulerPort）驱动。
 * 时钟经构造注入，便于穷举超时。
 */
public final class RttTracker {

    private final long timeoutMillis;
    private final LongSupplier clock;
    /** nonce → 发送时刻。 */
    private final Map<Long, Long> outstanding = new ConcurrentHashMap<>();

    public RttTracker(long timeoutMillis, LongSupplier clock) {
        this.timeoutMillis = timeoutMillis;
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /** 记录一次 Ping 发送。 */
    public void onPingSent(long nonce) {
        outstanding.put(nonce, clock.getAsLong());
    }

    /**
     * 收到 Pong：返回 RTT（毫秒）；若该 nonce 未在追踪（重复 / 过期 / 未知）则返回空。
     */
    public OptionalLong onPongReceived(long nonce) {
        Long sentAt = outstanding.remove(nonce);
        if (sentAt == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(clock.getAsLong() - sentAt);
    }

    /** 清扫超时未回的 Ping，返回其 nonce 列表（疑似丢失，供上层判定掉线 / 触发重同步）。 */
    public List<Long> sweepTimeouts() {
        long now = clock.getAsLong();
        List<Long> lost = new ArrayList<>();
        outstanding.entrySet().removeIf(e -> {
            if (now - e.getValue() > timeoutMillis) {
                lost.add(e.getKey());
                return true;
            }
            return false;
        });
        return lost;
    }

    /** 当前在途未回的 Ping 数。 */
    public int pendingCount() {
        return outstanding.size();
    }
}
