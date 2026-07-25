package top.wcpe.mc.mpmt.core.domain.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 自有 EventBus 的 L0 内核默认实现（平台无关，线程安全，零第三方依赖）。
 *
 * <p>按事件的<b>精确类型</b>分发：发布时取 {@code event.getClass()} 查订阅表。设计取舍——
 * 精确匹配最简单且满足域间协作需要（域订阅其关心的具体事件类型），暂不做父类型 / 接口层级分发（YAGNI）。
 *
 * <p>线程安全：订阅表用 {@link ConcurrentHashMap} + {@link CopyOnWriteArrayList}，支持并发订阅与发布；
 * 单个订阅者抛出的运行期异常被捕获隔离（中文 WARNING 日志），不影响其余订阅者，亦不向发布方传播。
 * 用 JDK 自带 {@link Logger} 记录以保持 L0 零第三方依赖。
 */
public final class SimpleEventBus implements EventBusPort {

    private static final Logger LOGGER = Logger.getLogger(SimpleEventBus.class.getName());

    /** 事件精确类型 → 订阅者列表。 */
    private final ConcurrentMap<Class<?>, CopyOnWriteArrayList<Consumer<? super DomainEvent>>> subscribers =
            new ConcurrentHashMap<>();

    @Override
    public <E extends DomainEvent> void subscribe(Class<E> eventType, Consumer<E> handler) {
        Objects.requireNonNull(eventType, "eventType 不能为空");
        Objects.requireNonNull(handler, "handler 不能为空");
        // 分发按精确类型匹配（map 键 = 事件精确类型），故传入处理器只会收到该精确类型的事件，向上转型安全
        @SuppressWarnings("unchecked")
        Consumer<? super DomainEvent> erased = (Consumer<? super DomainEvent>) handler;
        subscribers.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>()).add(erased);
    }

    @Override
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        List<Consumer<? super DomainEvent>> handlers = subscribers.get(event.getClass());
        if (handlers == null) {
            // 无订阅者：静默返回，不视为错误
            return;
        }
        for (Consumer<? super DomainEvent> handler : handlers) {
            try {
                handler.accept(event);
            } catch (RuntimeException ex) {
                // 隔离单个订阅者异常，保证其余订阅者照常收到
                LOGGER.log(Level.WARNING, "事件订阅者处理异常，已隔离不影响其他订阅者，事件类型=" + event.getClass().getName(), ex);
            }
        }
    }
}
