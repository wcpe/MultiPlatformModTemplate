package top.wcpe.mc.mpmt.core.domain.event;

/**
 * 领域事件标记接口。
 *
 * <p>所有经自有 EventBus 发布 / 订阅的事件都实现它。领域事件是平台无关的纯值对象，
 * 不得携带任何平台原生类型（ADR-0001 / ADR-0011）。
 */
public interface DomainEvent {
}
