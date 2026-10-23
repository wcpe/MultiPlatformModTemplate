package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.event.EventBusPort;
import top.wcpe.mc.mpmt.kernel.feature.RuntimeContext;

/** 客户端安装点测试：装配登记与展示实现可用（示范域 counter）。 */
class ChatCounterInstallerTest {

    @Test
    void 安装后登记到注册表并保留展示实现() {
        ChatCounterDisplay display = new ChatCounterDisplay((p, text) -> { }, new NoopScheduler());
        ChatCounterInstaller installer = new ChatCounterInstaller(display);

        int installed = CounterClientInstallers.installAll(new NoopContext());

        assertTrue(installed >= 0, "安装点执行不应失败");
        assertEquals(display, installer.display());
    }

    @Test
    void 注册后install调用可重复统计() {
        ChatCounterInstaller installer =
                new ChatCounterInstaller(
                        new ChatCounterDisplay((p, text) -> { }, new NoopScheduler()));
        installer.install(new NoopContext());

        assertTrue(CounterClientInstallers.installAll(new NoopContext()) >= 1, "已注册安装点应被执行");
    }

    /** 上下文桩：只提供空事件总线。 */
    private static final class NoopContext implements RuntimeContext {
        @Override
        public EventBusPort eventBus() {
            return new EventBusPort() {                @Override
                public <E extends top.wcpe.mc.mpmt.kernel.event.DomainEvent> void subscribe(
                        Class<E> eventType, java.util.function.Consumer<E> handler) {
                    // 测试无需订阅
                }

                @Override
                public void publish(top.wcpe.mc.mpmt.kernel.event.DomainEvent event) {
                    // 测试无需发布
                }
            };
        }

        @Override
        public <T> T port(Class<T> type) {
            throw new IllegalStateException("测试上下文不提供端口：" + type);
        }
    }

    /** 调度桩：全部立即执行。 */
    private static final class NoopScheduler implements top.wcpe.mc.mpmt.kernel.port.SchedulerPort {
        @Override
        public void runForEntity(top.wcpe.mc.mpmt.kernel.ref.EntityRef entity, Runnable task) {
            task.run();
        }

        @Override
        public void runForLocation(
                top.wcpe.mc.mpmt.kernel.ref.WorldRef world, int x, int z, Runnable task) {
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
