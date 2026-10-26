package top.wcpe.mc.mpmt.examples.counter;

import top.wcpe.mc.mpmt.kernel.feature.RuntimeContext;

/**
 * 客户端内部装配接口（示范域 counter）。
 *
 * <p>{@code client-internal} 只放 interface 与安装点 object，不放实现（ADR-0030）：
 * 具体实现在 {@code client}，本接口用于在域内解耦"谁注册了什么"。
 */
public interface CounterClientInstallPoint {

    /**
     * 按下运行时上下文完成客户端侧装配。
     *
     * @param context 运行时上下文
     */
    void install(RuntimeContext context);
}
