package top.wcpe.mc.mpmt.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 心跳 RTT 追踪：RTT 计算 / 未知 Pong / 超时清扫穷举（FR-28）。 */
class RttTrackerTest {

    @Test
    @DisplayName("Ping→Pong 计算 RTT")
    void 计算RTT() {
        AtomicLong clock = new AtomicLong(0L);
        RttTracker tracker = new RttTracker(1000L, clock::get);

        tracker.onPingSent(42L);
        clock.set(50L);
        OptionalLong rtt = tracker.onPongReceived(42L);

        assertTrue(rtt.isPresent());
        assertEquals(50L, rtt.getAsLong());
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    @DisplayName("未知 / 重复 Pong 返回空")
    void 未知Pong() {
        RttTracker tracker = new RttTracker(1000L, () -> 0L);
        assertFalse(tracker.onPongReceived(7L).isPresent());

        tracker.onPingSent(7L);
        assertTrue(tracker.onPongReceived(7L).isPresent());
        // 重复回应
        assertFalse(tracker.onPongReceived(7L).isPresent());
    }

    @Test
    @DisplayName("超时清扫：过期 Ping 被判疑似丢失")
    void 超时清扫() {
        AtomicLong clock = new AtomicLong(0L);
        RttTracker tracker = new RttTracker(1000L, clock::get);

        tracker.onPingSent(1L);
        tracker.onPingSent(2L);
        clock.set(2000L);

        List<Long> lost = tracker.sweepTimeouts();
        assertTrue(lost.contains(1L));
        assertTrue(lost.contains(2L));
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    @DisplayName("未超时不清扫")
    void 未超时不清扫() {
        AtomicLong clock = new AtomicLong(0L);
        RttTracker tracker = new RttTracker(1000L, clock::get);
        tracker.onPingSent(1L);
        clock.set(500L);
        assertTrue(tracker.sweepTimeouts().isEmpty());
        assertEquals(1, tracker.pendingCount());
    }
}
