package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.event.DomainEvent;
import top.wcpe.mc.mpmt.kernel.event.EventBusPort;
import top.wcpe.mc.mpmt.kernel.feature.RuntimeContext;

/** 内部安装点测试：注册 / 执行 / 遍历中新增不炸（示范域 counter）。 */
class CounterClientInstallersTest {

    @Test
    void 安装点登记后可被执行() {
        AtomicInteger calls = new AtomicInteger();
        CounterClientInstallPoint installer = context -> calls.incrementAndGet();
        CounterClientInstallers.register(installer);

        int executed = CounterClientInstallers.installAll(new NoopContext());

        assertTrue(executed >= 1, "已注册安装点应被执行");
        assertTrue(calls.get() >= 1, "安装点逻辑应被调用");
    }

    @Test
    void 安装过程中新增安装点不会抛并发修改异常() {
        // 快照遍历的关键保证：安装点自身再注册新安装点不应炸
        CounterClientInstallPoint selfRegistering =
                context -> CounterClientInstallers.register(ctx -> { });
        CounterClientInstallers.register(selfRegistering);

        assertEquals(
                true,
                CounterClientInstallers.installAll(new NoopContext()) >= 1,
                "遍历中新增应安全");
    }

    /** 上下文桩：只提供空事件总线。 */
    private static final class NoopContext implements RuntimeContext {
        @Override
        public EventBusPort eventBus() {
            return new EventBusPort() {
                @Override
                public <E extends DomainEvent> void subscribe(
                        Class<E> eventType, java.util.function.Consumer<E> handler) {
                    // 测试无需订阅
                }

                @Override
                public void publish(DomainEvent event) {
                    // 测试无需发布
                }
            };
        }

        @Override
        public <T> T port(Class<T> type) {
            throw new IllegalStateException("测试上下文不提供端口：" + type);
        }
    }
}
