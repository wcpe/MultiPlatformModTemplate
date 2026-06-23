package top.wcpe.mc.mpmt.core.domain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 自有 EventBus 默认实现的纯 JVM 单测（覆盖正常 / 边界 / 错误 / 并发路径，对应 testing-and-quality §2「EventBus」）。 */
class SimpleEventBusTest {

    /** 测试用领域事件。 */
    private static final class FooEvent implements DomainEvent {
        final int value;

        FooEvent(int value) {
            this.value = value;
        }
    }

    /** 另一种测试用领域事件，验证类型隔离。 */
    private static final class BarEvent implements DomainEvent {
    }

    @Test
    @DisplayName("订阅后发布：处理器按类型收到事件")
    void 订阅后发布_处理器收到事件() {
        SimpleEventBus bus = new SimpleEventBus();
        List<Integer> received = new ArrayList<>();
        bus.subscribe(FooEvent.class, e -> received.add(e.value));

        bus.publish(new FooEvent(42));

        assertEquals(1, received.size());
        assertEquals(42, received.get(0));
    }

    @Test
    @DisplayName("同类型多订阅者：全部按注册顺序收到")
    void 同类型多订阅者_全部收到() {
        SimpleEventBus bus = new SimpleEventBus();
        List<String> order = new ArrayList<>();
        bus.subscribe(FooEvent.class, e -> order.add("a"));
        bus.subscribe(FooEvent.class, e -> order.add("b"));

        bus.publish(new FooEvent(1));

        assertEquals(Arrays.asList("a", "b"), order);
    }

    @Test
    @DisplayName("类型隔离：FooEvent 订阅者不收到 BarEvent")
    void 不同类型_互不串扰() {
        SimpleEventBus bus = new SimpleEventBus();
        AtomicInteger fooCount = new AtomicInteger();
        bus.subscribe(FooEvent.class, e -> fooCount.incrementAndGet());

        bus.publish(new BarEvent());

        assertEquals(0, fooCount.get());
    }

    @Test
    @DisplayName("无订阅者：发布不抛异常")
    void 无订阅者_发布不报错() {
        SimpleEventBus bus = new SimpleEventBus();
        assertDoesNotThrow(() -> bus.publish(new FooEvent(1)));
    }

    @Test
    @DisplayName("异常隔离：单个订阅者抛异常不影响其他订阅者")
    void 订阅者抛异常_不影响其他() {
        SimpleEventBus bus = new SimpleEventBus();
        AtomicInteger secondReceived = new AtomicInteger();
        bus.subscribe(FooEvent.class, e -> {
            throw new IllegalStateException("故意抛出，验证隔离");
        });
        bus.subscribe(FooEvent.class, e -> secondReceived.incrementAndGet());

        assertDoesNotThrow(() -> bus.publish(new FooEvent(1)));
        assertEquals(1, secondReceived.get());
    }

    @Test
    @DisplayName("参数校验：null 入参抛 NPE")
    void null入参_抛NPE() {
        SimpleEventBus bus = new SimpleEventBus();
        assertThrows(NullPointerException.class, () -> bus.subscribe(null, e -> {}));
        assertThrows(NullPointerException.class, () -> bus.subscribe(FooEvent.class, null));
        assertThrows(NullPointerException.class, () -> bus.publish(null));
    }

    @Test
    @DisplayName("线程安全：并发订阅与发布不丢事件、不抛异常")
    void 并发订阅与发布_线程安全() throws InterruptedException {
        SimpleEventBus bus = new SimpleEventBus();
        AtomicInteger received = new AtomicInteger();
        bus.subscribe(FooEvent.class, e -> received.incrementAndGet());

        int threads = 8;
        int perThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 边发布边订阅，验证并发结构安全（新订阅者数量另行断言）
                        if (i == 0) {
                            bus.subscribe(BarEvent.class, e -> {});
                        }
                        bus.publish(new FooEvent(tid));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(true, finished, "并发任务应在超时内完成");
        assertEquals(threads * perThread, received.get(), "FooEvent 应被无丢失地全部投递");
    }
}
