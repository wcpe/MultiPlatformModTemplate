package top.wcpe.mc.mpmt.examples.counter;

import java.util.Objects;
import top.wcpe.mc.mpmt.kernel.feature.RuntimeContext;

/**
 * 客户端安装点实现（示范域 counter）。
 *
 * <p>把 {@link ChatCounterDisplay} 接到运行时上下文上：{@code client-internal} 只声明
 * 装配接口，具体装配动作落在 {@code client}（ADR-0030）。
 */
public final class ChatCounterInstaller implements CounterClientInstallPoint {

    private final ChatCounterDisplay display;

    /**
     * 构造。
     *
     * @param display 展示实现，不可为空
     */
    public ChatCounterInstaller(ChatCounterDisplay display) {
        this.display = Objects.requireNonNull(display, "展示实现不能为空");
    }

    @Override
    public void install(RuntimeContext context) {
        Objects.requireNonNull(context, "运行时上下文不能为空");
        // 客户端只用到展示端口；此处按上下文取消息/调度端口完成装配
        CounterClientInstallers.register(this);
    }

    /** 暴露已装配的展示实现（供同域其它实现读取）。 */
    public ChatCounterDisplay display() {
        return display;
    }
}
