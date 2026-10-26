package top.wcpe.mc.mpmt.examples.counter;

import top.wcpe.mc.mpmt.kernel.feature.RuntimeContext;

/**
 * 服务端内部装配接口（示范域 counter）。
 *
 * <p>{@code server-internal} 只放 interface 与安装点 object，不放实现（ADR-0030）：
 * 具体实现在 {@code server}。
 */
public interface CounterServerInstallPoint {

    /**
     * 按下运行时上下文完成服务端侧装配。
     *
     * @param context 运行时上下文
     */
    void install(RuntimeContext context);
}
