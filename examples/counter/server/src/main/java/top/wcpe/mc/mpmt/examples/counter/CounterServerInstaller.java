package top.wcpe.mc.mpmt.examples.counter;

import java.util.Objects;
import top.wcpe.mc.mpmt.kernel.feature.RuntimeContext;

/**
 * 服务端安装点实现（示范域 counter）。
 *
 * <p>把 {@link CounterServerService} 订阅到自有 EventBus：{@code server-internal} 只声明
 * 装配接口，具体装配动作落在 {@code server}（ADR-0030）。
 */
public final class CounterServerInstaller implements CounterServerInstallPoint {

    private final CounterServerService service;

    /**
     * 构造。
     *
     * @param service 服务端计数服务，不可为空
     */
    public CounterServerInstaller(CounterServerService service) {
        this.service = Objects.requireNonNull(service, "计数服务不能为空");
    }

    @Override
    public void install(RuntimeContext context) {
        Objects.requireNonNull(context, "运行时上下文不能为空");
        // 域内只经 EventBus 协作（ADR-0011）：订阅共用加入事件
        service.register(context.eventBus());
        // 这里不能再调 CounterServerInstallers.register(this)：install() 是由 installAll 遍历调用的，
        // 在遍历中把自己重新登记进注册表会让列表每次都增长一份，多次安装后重复执行且无上限。
    }

    /** 暴露已装配的计数服务（供同域其它实现读取）。 */
    public CounterServerService service() {
        return service;
    }
}
