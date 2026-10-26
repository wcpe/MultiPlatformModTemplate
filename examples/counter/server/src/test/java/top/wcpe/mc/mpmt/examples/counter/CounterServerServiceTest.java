package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.event.DomainEvent;
import top.wcpe.mc.mpmt.kernel.event.EventBusPort;
import top.wcpe.mc.mpmt.kernel.port.PersistencePort;
import top.wcpe.mc.mpmt.kernel.port.SchedulerPort;
import top.wcpe.mc.mpmt.kernel.ref.EntityRef;
import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;
import top.wcpe.mc.mpmt.kernel.ref.WorldRef;

/** 服务端实现测试：订阅共用事件、首次加入记录与离开释放句柄（示范域 counter）。 */
class CounterServerServiceTest {

    @Test
    void 订阅加入事件后记录首次加入并启动周期提示() {
        InMemoryPersistence store = new InMemoryPersistence();
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingEventBus bus = new RecordingEventBus();
        CounterServerService service = new CounterServerService(store, scheduler, () -> 4242L);
        service.register(bus);

        PlayerRef player = new PlayerRef(UUID.randomUUID(), "Bob");
        bus.publish(new CounterJoinEvent(player, 1));

        assertEquals("4242", store.read(CounterKeys.NAMESPACE, CounterKeys.firstJoinKey(player.getUuid().toString())).orElse(""));
        assertEquals(1, scheduler.timers);
    }

    @Test
    void 玩家离开时关闭周期句柄() {
        RecordingScheduler scheduler = new RecordingScheduler();
        CounterServerService service = new CounterServerService(new InMemoryPersistence(), scheduler, () -> 0L);
        service.register(new RecordingEventBus());
        PlayerRef player = new PlayerRef(UUID.randomUUID(), "Carol");

        service.onPlayerLeft(player);

        assertEquals(0, scheduler.openHandles());
    }

    @Test
    void 查询走的共用实现累计次数() {
        InMemoryPersistence store = new InMemoryPersistence();
        PlayerRef player = new PlayerRef(UUID.randomUUID(), "Dave");
        store.write(CounterKeys.NAMESPACE, CounterKeys.joinCountKey(player.getUuid().toString()), "5");

        CounterServerService service = new CounterServerService(store, new RecordingScheduler(), () -> 0L);

        assertEquals(5, service.joinCount(player));
    }

    /** 内存持久化桩。 */
    private static final class InMemoryPersistence implements PersistencePort {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<String> read(String namespace, String key) {
            return Optional.ofNullable(values.get(namespace + "/" + key));
        }

        @Override
        public void write(String namespace, String key, String value) {
            values.put(namespace + "/" + key, value);
        }
    }

    /** 事件总线桩：按类型精确分发。 */
    private static final class RecordingEventBus implements EventBusPort {
        private final Map<Class<?>, Consumer<DomainEvent>> handlers = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <E extends DomainEvent> void subscribe(Class<E> eventType, Consumer<E> handler) {
            handlers.put(eventType, (Consumer<DomainEvent>) handler);
        }

        @Override
        public void publish(DomainEvent event) {
            Consumer<DomainEvent> handler = handlers.get(event.getClass());
            if (handler != null) {
                handler.accept(event);
            }
        }
    }

    /** 调度桩：记录周期句柄数量，异步立即执行。 */
    private static final class RecordingScheduler implements SchedulerPort {
        private int timers;
        private int open;

        int timers() {
            return timers;
        }

        int openHandles() {
            return open;
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
            task.run();
        }

        @Override
        public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
            timers++;
            open++;
            return () -> open--;
        }
    }
}
