package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.event.SimpleEventBus;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;
import top.wcpe.mc.mpmt.domain.capability.PlayerLeftEvent;

/** Counter（FR-18）L0 逻辑穷举：首次加入 / 计数 / 调度 / EventBus / 句柄释放。 */
class PlayerJoinCounterServiceTest {

    private static final long FIXED_NOW = 123456789L;
    private static final LongSupplier CLOCK = () -> FIXED_NOW;

    private static PlayerRef player() {
        return new PlayerRef(UUID.randomUUID(), "Steve");
    }

    private static PlayerJoinCounterService service(
            PersistencePort persistence, MessagePort message, SchedulerPort scheduler) {
        return new PlayerJoinCounterService(persistence, message, scheduler, CLOCK);
    }

    @Test
    @DisplayName("首次加入：计数从 0→1，发「你已加入 1 次」")
    void 首次加入() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        FakeScheduler scheduler = new FakeScheduler();
        PlayerRef p = player();

        service(persistence, message, scheduler).onPlayerJoined(p);

        assertEquals(2, persistence.store.size());
        assertEquals(
                "1",
                persistence.store.get(
                        PlayerJoinCounterService.NAMESPACE
                                + "/"
                                + PlayerJoinCounterService.JOIN_COUNT_KEY_PREFIX
                                + p.getUuid()));
        assertEquals(
                Long.toString(FIXED_NOW),
                persistence.store.get(
                        PlayerJoinCounterService.NAMESPACE
                                + "/"
                                + PlayerJoinCounterService.FIRST_JOIN_KEY_PREFIX
                                + p.getUuid()));
        assertEquals(1, message.sent.size());
        assertEquals("你已加入 1 次", message.sent.get(0));
        assertEquals(1, scheduler.asyncCount);
        assertTrue(scheduler.entityCalls.contains(new EntityRef(p.getUuid())));
        assertEquals(PlayerJoinCounterService.REMINDER_DELAY_TICKS, scheduler.timerDelay);
        assertEquals(PlayerJoinCounterService.REMINDER_PERIOD_TICKS, scheduler.timerPeriod);
    }

    @Test
    @DisplayName("同一玩家多次加入：计数递增")
    void 多次加入递增() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        FakeScheduler scheduler = new FakeScheduler();
        PlayerJoinCounterService svc = service(persistence, message, scheduler);
        PlayerRef p = player();

        svc.onPlayerJoined(p);
        svc.onPlayerJoined(p);
        svc.onPlayerJoined(p);

        assertEquals(
                "3",
                persistence.read(
                                PlayerJoinCounterService.NAMESPACE,
                                PlayerJoinCounterService.JOIN_COUNT_KEY_PREFIX + p.getUuid())
                        .get());
        assertEquals(3, message.sent.size());
        assertEquals("你已加入 3 次", message.sent.get(2));
        assertEquals(
                Long.toString(FIXED_NOW),
                persistence.read(
                                PlayerJoinCounterService.NAMESPACE,
                                PlayerJoinCounterService.FIRST_JOIN_KEY_PREFIX + p.getUuid())
                        .get());
        assertEquals(2, scheduler.closeCount);
    }

    @Test
    @DisplayName("同一玩家并发加入：持久化读写不会丢失次数")
    void 同一玩家并发加入不丢失次数() throws InterruptedException {
        ConcurrentJoinPersistence persistence = new ConcurrentJoinPersistence();
        ConcurrentScheduler scheduler = new ConcurrentScheduler(1);
        PlayerRef p = player();
        PlayerJoinCounterService svc = service(persistence, new FakeMessage(), scheduler);

        svc.onPlayerJoined(p);
        svc.onPlayerJoined(p);
        assertTrue(scheduler.awaitTasks());

        assertEquals(
                "2",
                persistence.read(
                                PlayerJoinCounterService.NAMESPACE,
                                PlayerJoinCounterService.JOIN_COUNT_KEY_PREFIX + p.getUuid())
                        .get());
    }

    @Test
    @DisplayName("不同玩家计数互不干扰")
    void 多玩家隔离() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        FakeScheduler scheduler = new FakeScheduler();
        PlayerJoinCounterService svc = service(persistence, message, scheduler);
        PlayerRef a = player();
        PlayerRef b = player();

        svc.onPlayerJoined(a);
        svc.onPlayerJoined(b);
        svc.onPlayerJoined(a);

        assertEquals(
                "2",
                persistence.store.get(
                        PlayerJoinCounterService.NAMESPACE
                                + "/"
                                + PlayerJoinCounterService.JOIN_COUNT_KEY_PREFIX
                                + a.getUuid()));
        assertEquals(
                "1",
                persistence.store.get(
                        PlayerJoinCounterService.NAMESPACE
                                + "/"
                                + PlayerJoinCounterService.JOIN_COUNT_KEY_PREFIX
                                + b.getUuid()));
        assertEquals(3, message.sent.size());
    }

    @Test
    @DisplayName("经 EventBus 订阅：发布加入事件被响应")
    void 经事件总线订阅() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        FakeScheduler scheduler = new FakeScheduler();
        PlayerJoinCounterService svc = service(persistence, message, scheduler);
        SimpleEventBus bus = new SimpleEventBus();
        svc.register(bus);
        PlayerRef p = player();

        bus.publish(new PlayerJoinedEvent(p));

        assertEquals("你已加入 1 次", message.sent.get(0));
        assertEquals(2, persistence.store.size());

        bus.publish(new PlayerLeftEvent(p));
        assertEquals(1, scheduler.closeCount);
    }

    @Test
    @DisplayName("周期提示：触发时按玩家归属发送加入次数")
    void 周期提示() {
        FakeMessage message = new FakeMessage();
        FakeScheduler scheduler = new FakeScheduler();
        PlayerRef p = player();

        service(new FakePersistence(), message, scheduler).onPlayerJoined(p);
        int before = message.sent.size();
        scheduler.timerTask.run();

        assertEquals(before + 1, message.sent.size());
        assertEquals("你已加入 1 次", message.sent.get(message.sent.size() - 1));
        assertTrue(scheduler.entityCalls.contains(new EntityRef(p.getUuid())));
    }

    @Test
    @DisplayName("离开：释放周期提示句柄，重复离开无副作用")
    void 离开释放句柄() {
        FakeScheduler scheduler = new FakeScheduler();
        PlayerJoinCounterService svc = service(new FakePersistence(), new FakeMessage(), scheduler);
        PlayerRef p = player();

        svc.onPlayerJoined(p);
        svc.onPlayerLeft(p);
        svc.onPlayerLeft(p);

        assertEquals(1, scheduler.closeCount);
    }

    @Test
    @DisplayName("脏数据计数按 0 处理，不阻断主流程")
    void 脏数据容错() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        FakeScheduler scheduler = new FakeScheduler();
        PlayerRef p = player();
        persistence.write(
                PlayerJoinCounterService.NAMESPACE,
                PlayerJoinCounterService.JOIN_COUNT_KEY_PREFIX + p.getUuid(),
                "not-a-number");

        service(persistence, message, scheduler).onPlayerJoined(p);

        assertEquals(
                "1",
                persistence.read(
                                PlayerJoinCounterService.NAMESPACE,
                                PlayerJoinCounterService.JOIN_COUNT_KEY_PREFIX + p.getUuid())
                        .get());
        assertEquals("你已加入 1 次", message.sent.get(0));
    }

    @Test
    @DisplayName("构造 / 订阅入参为空即拒")
    void 入参校验() {
        FakePersistence p = new FakePersistence();
        FakeMessage m = new FakeMessage();
        FakeScheduler s = new FakeScheduler();
        assertThrows(NullPointerException.class, () -> new PlayerJoinCounterService(null, m, s, CLOCK));
        assertThrows(NullPointerException.class, () -> new PlayerJoinCounterService(p, null, s, CLOCK));
        assertThrows(NullPointerException.class, () -> new PlayerJoinCounterService(p, m, null, CLOCK));
        assertThrows(NullPointerException.class, () -> new PlayerJoinCounterService(p, m, s, null));
        assertThrows(NullPointerException.class, () -> service(p, m, s).register(null));
    }

    // —— 测试替身（手写假端口，纯内存）——

    /** 假持久化：内存 map，键为 namespace/key。 */
    private static final class FakePersistence implements PersistencePort {
        final Map<String, String> store = new HashMap<>();

        @Override
        public Optional<String> read(String namespace, String key) {
            return Optional.ofNullable(store.get(namespace + "/" + key));
        }

        @Override
        public void write(String namespace, String key, String value) {
            store.put(namespace + "/" + key, value);
        }
    }

    /** 假消息端口：记录发出的文本。 */
    private static final class FakeMessage implements MessagePort {
        final List<String> sent = new ArrayList<>();

        @Override
        public void send(PlayerRef player, String text) {
            sent.add(text);
        }
    }

    /** 假调度：立即运行一次性任务，记录周期任务与关闭次数。 */
    private static final class FakeScheduler implements SchedulerPort {
        final List<EntityRef> entityCalls = new ArrayList<>();
        Runnable timerTask;
        long timerDelay;
        long timerPeriod;
        int asyncCount;
        int closeCount;

        @Override
        public void runForEntity(EntityRef entity, Runnable task) {
            entityCalls.add(entity);
            task.run();
        }

        @Override
        public void runForLocation(WorldRef world, int x, int z, Runnable task) {
            task.run();
        }

        @Override
        public void runGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            asyncCount++;
            task.run();
        }

        @Override
        public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
            timerDelay = delayTicks;
            timerPeriod = periodTicks;
            timerTask = task;
            return () -> closeCount++;
        }
    }

    /** 受控并发持久化：让两个任务读取相同的计数后再继续写入。 */
    private static final class ConcurrentJoinPersistence implements PersistencePort {
        private final Map<String, String> store = new ConcurrentHashMap<>();
        private final CountDownLatch joinCountReads = new CountDownLatch(2);

        @Override
        public Optional<String> read(String namespace, String key) {
            String namespacedKey = namespace + "/" + key;
            String value = store.get(namespacedKey);
            if (key.startsWith(PlayerJoinCounterService.JOIN_COUNT_KEY_PREFIX)) {
                joinCountReads.countDown();
                waitForPeerRead(joinCountReads);
            }
            return Optional.ofNullable(value);
        }

        @Override
        public void write(String namespace, String key, String value) {
            store.put(namespace + "/" + key, value);
        }

    }

    /** 受控异步调度：每个一次性任务独立执行，供并发读写回归测试使用。 */
    private static final class ConcurrentScheduler implements SchedulerPort {
        private final CountDownLatch completed;

        ConcurrentScheduler(int taskCount) {
            completed = new CountDownLatch(taskCount);
        }

        @Override
        public void runForEntity(EntityRef entity, Runnable task) {
            task.run();
        }

        @Override
        public void runForLocation(WorldRef world, int x, int z, Runnable task) {
            task.run();
        }

        @Override
        public void runGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            Thread worker = new Thread(() -> {
                try {
                    task.run();
                } finally {
                    completed.countDown();
                }
            });
            worker.start();
        }

        @Override
        public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
            return () -> { };
        }

        boolean awaitTasks() throws InterruptedException {
            return completed.await(5, TimeUnit.SECONDS);
        }
    }

    private static void waitForPeerRead(CountDownLatch latch) {
        try {
            latch.await(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发测试被中断", ex);
        }
    }
}
