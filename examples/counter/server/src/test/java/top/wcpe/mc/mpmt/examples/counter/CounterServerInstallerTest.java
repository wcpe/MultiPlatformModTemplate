package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.event.DomainEvent;
import top.wcpe.mc.mpmt.kernel.event.EventBusPort;
import top.wcpe.mc.mpmt.kernel.feature.RuntimeContext;
import top.wcpe.mc.mpmt.kernel.port.PersistencePort;
import top.wcpe.mc.mpmt.kernel.port.SchedulerPort;
import top.wcpe.mc.mpmt.kernel.ref.EntityRef;
import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;
import top.wcpe.mc.mpmt.kernel.ref.WorldRef;

/** 服务端安装点测试：装到上下文即完成事件订阅（示范域 counter）。 */
class CounterServerInstallerTest {

    @Test
    void 安装后订阅加入事件并登记到注册表() {
        RecordingBus bus = new RecordingBus();
        CounterServerInstaller installer =
                new CounterServerInstaller(
                        new CounterServerService(new Store(), new Sched(), () -> 1L));

        installer.install(context(bus));

        assertTrue(bus.subscribed, "安装后必须订阅共用加入事件");
        assertTrue(CounterServerInstallers.installAll(context(bus)) >= 1, "已注册安装点应被执行");
    }

    @Test
    void 重置清空累计次数为非零基准() {
        Store store = new Store();
        PlayerRef player = new PlayerRef(UUID.randomUUID(), "Eve");
        CounterServerService service = new CounterServerService(store, new Sched(), () -> 0L);
        store.write(CounterKeys.NAMESPACE, CounterKeys.joinCountKey(player.getUuid().toString()), "9");

        service.reset(player);

        assertEquals(9, service.joinCount(player), "reset 只重置首次加入时间，不改累计次数");
        assertTrue(
                store.read(CounterKeys.NAMESPACE, CounterKeys.firstJoinKey(player.getUuid().toString()))
                        .isPresent(),
                "reset 应写入首次加入记录");
    }

    private static RuntimeContext context(RecordingBus bus) {
        return new RuntimeContext() {
            @Override
            public EventBusPort eventBus() {
                return bus;
            }

            @Override
            public <T> T port(Class<T> type) {
                throw new IllegalStateException("测试上下文不提供端口：" + type);
            }
        };
    }

    /** 事件总线桩：记录是否发生过订阅。 */
    private static final class RecordingBus implements EventBusPort {
        private boolean subscribed;

        @Override
        public <E extends DomainEvent> void subscribe(Class<E> eventType, Consumer<E> handler) {
            subscribed = true;
        }

        @Override
        public void publish(DomainEvent event) {
            // 测试无需发布
        }
    }

    /** 内存持久化桩。 */
    private static final class Store implements PersistencePort {
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

    /** 调度桩：立即执行。 */
    private static final class Sched implements SchedulerPort {
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
            return () -> { };
        }
    }
}
