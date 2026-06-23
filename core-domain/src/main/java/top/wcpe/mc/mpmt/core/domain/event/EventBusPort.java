package top.wcpe.mc.mpmt.core.domain.event;

import java.util.function.Consumer;

/**
 * 自有 EventBus 的订阅 / 发布接口（L0 内核）。
 *
 * <p>承载功能域之间的事件转发：各功能域只 {@link #publish} / {@link #subscribe} 事件、
 * 不直接调用兄弟域（ADR-0011）。它是"接口 + 内核默认实现（{@link SimpleEventBus}）"一套机制，
 * <b>不是平台端口</b>——无 L3 平台实现。平台原生事件由各 L3 适配器转成领域事件后投递到本总线。
 *
 * <p>线程模型：投递可能发生在任意线程；订阅者若需触碰游戏 / 世界状态，须自行经
 * {@code SchedulerPort} 按归属切到正确线程后再操作（Folia 无单一主线程，见 ADR-0013）。
 */
public interface EventBusPort {

    /**
     * 订阅某类型的领域事件。
     *
     * @param eventType 事件类型（按精确类型匹配分发）
     * @param handler   处理器
     * @param <E>       事件类型
     */
    <E extends DomainEvent> void subscribe(Class<E> eventType, Consumer<E> handler);

    /**
     * 发布一个领域事件，投递给其精确类型的全部订阅者。
     *
     * <p>无订阅者时静默返回；单个订阅者抛出的运行期异常被隔离、不影响其他订阅者。
     *
     * @param event 领域事件
     */
    void publish(DomainEvent event);
}
