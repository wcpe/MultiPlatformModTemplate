package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.event.EventBusPort;
import top.wcpe.mc.mpmt.kernel.feature.RuntimeContext;

/** 客户端安装点测试：装配登记与展示实现可用（示范域 counter）。 */
class ChatCounterInstallerTest {

    @BeforeEach
    void clearRegistry() {
        CounterClientInstallers.clearForTest();
    }

    @Test
    void 安装后保留展示实现且装配不报错() {
        ChatCounterDisplay display = new ChatCounterDisplay((p, text) -> { }, new NoopScheduler());
        ChatCounterInstaller installer = new ChatCounterInstaller(display);

        installer.install(new NoopContext());

        assertEquals(display, installer.display(), "安装后应保留构造期注入的展示实现");
    }

    @Test
    void 安装点登记后由installAll执行且不自我复制() {
        ChatCounterInstaller installer =
                new ChatCounterInstaller(
                        new ChatCounterDisplay((p, text) -> { }, new NoopScheduler()));
        CounterClientInstallers.register(installer);

        // install() 不得在遍历中把自己重新登记：连续两轮都只应执行同一个安装点。
        // 曾经实现里有 register(this)，两轮之后注册表会膨胀成 2 个，这条断言会红。
        assertEquals(1, CounterClientInstallers.installAll(new NoopContext()), "第一轮应执行一个安装点");
        assertEquals(1, CounterClientInstallers.installAll(new NoopContext()), "第二轮仍应只有一个安装点");
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
