package top.wcpe.mc.mpmt.core.runtime;

import top.wcpe.mc.mpmt.core.domain.event.EventBusPort;

/**
 * 运行时上下文：玩法特性（{@link Feature}）在生命周期回调中拿到的能力入口。
 *
 * <p>只暴露平台无关能力——自有 EventBus 与已装配的端口；特性经它请求能力，不感知底层平台。
 */
public interface RuntimeContext {

    /** 自有 EventBus（域间发布 / 订阅）。 */
    EventBusPort eventBus();

    /**
     * 取出某端口实现。
     *
     * @throws IllegalStateException 端口未注册
     */
    <T> T port(Class<T> type);
}
