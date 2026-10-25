package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void clearRegistry() {
        CounterServerInstallers.clearForTest();
    }

    @Test
    void 安装后订阅加入事件但不自我登记() {
        RecordingBus bus = new RecordingBus();
        CounterServerInstaller installer =
                new CounterServerInstaller(
                        new CounterServerService(new Store(), new Sched(), () -> 1L));

        installer.install(context(bus));

        assertTrue(bus.subscribed, "安装后必须订阅共用加入事件");
        // install() 是由 installAll 遍历调用的，若在其中 register(this) 会让注册表每轮增长一份。
        // 因此安装完不应有任何自我登记：由外部显式 register 才是唯一入口。
        assertEquals(0, CounterServerInstallers.installAll(context(bus)), "安装点不应在 install 中自我登记");
    }

    @Test
    void 重置把累计次数清零且保留首次加入时间() {
        Store store = new Store();
        PlayerRef player = new PlayerRef(UUID.randomUUID(), "Eve");
        CounterServerService service = new CounterServerService(store, new Sched(), () -> 0L);
        String uuid = player.getUuid().toString();
        store.write(CounterKeys.NAMESPACE, CounterKeys.joinCountKey(uuid), "9");
        store.write(CounterKeys.NAMESPACE, CounterKeys.firstJoinKey(uuid), "1700000000000");

        service.reset(player);

        // 契约（CounterAdminPort#reset）就是「重置该玩家的累计次数」，所以这里必须归零。
        // 曾经的实现改调 recordFirstJoin(uuid, 0L)，而它在键已存在时直接返回，对已存在玩家是 no-op，
        // 于是本条断言被写成 assertEquals(9, ...) 去迁就错误实现——那是把缺陷固化成规范。
        assertEquals(0, service.joinCount(player), "reset 必须把累计次数清零");
        assertEquals(
                1700000000000L,
                service.read(player).firstJoinMillis(),
                "reset 不应改动首次加入时间");
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
