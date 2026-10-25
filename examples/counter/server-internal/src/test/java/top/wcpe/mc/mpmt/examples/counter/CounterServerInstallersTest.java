package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.event.DomainEvent;
import top.wcpe.mc.mpmt.kernel.event.EventBusPort;
import top.wcpe.mc.mpmt.kernel.feature.RuntimeContext;

/** 服务端内部安装点测试：注册 / 执行 / 遍历中新增不炸（示范域 counter）。 */
class CounterServerInstallersTest {

    /**
     * 注册表是静态可变的，必须在每个用例前清空。
     *
     * <p>不清理会让用例互相污染，精确断言只能退化成 {@code >= 1} 的宽松写法。
     */
    @BeforeEach
    void clearRegistry() {
        CounterServerInstallers.clearForTest();
    }

    @Test
    void 安装点登记后可被执行() {
        AtomicInteger calls = new AtomicInteger();
        CounterServerInstallPoint installer = context -> calls.incrementAndGet();
        CounterServerInstallers.register(installer);

        int executed = CounterServerInstallers.installAll(new NoopContext());

        assertEquals(1, executed, "只注册了一个安装点，就应恰好执行一个");
        assertEquals(1, calls.get(), "安装点逻辑应被调用一次");
    }

    @Test
    void 安装过程中新增安装点不会抛并发修改异常() {
        AtomicInteger laterCalls = new AtomicInteger();
        CounterServerInstallPoint selfRegistering =
                context -> CounterServerInstallers.register(ctx -> laterCalls.incrementAndGet());
        CounterServerInstallers.register(selfRegistering);

        // 本轮只执行快照里的 1 个（selfRegistering），它新登记的那个不在本轮快照内
        assertEquals(1, CounterServerInstallers.installAll(new NoopContext()), "本轮只执行快照内的安装点");
        assertEquals(0, laterCalls.get(), "遍历中新增的安装点不应在本轮被执行");

        // 下一轮才会执行到它：此时注册表里是 2 个（原有的 + 新增的）
        assertEquals(2, CounterServerInstallers.installAll(new NoopContext()), "下一轮执行两个安装点");
        assertEquals(1, laterCalls.get(), "新增的安装点应在下一轮被调用一次");
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
