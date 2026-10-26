package top.wcpe.mc.mpmt.domain.capability;

import java.util.Objects;
import top.wcpe.mc.mpmt.kernel.feature.Feature;
import top.wcpe.mc.mpmt.kernel.feature.RuntimeContext;
import top.wcpe.mc.mpmt.kernel.port.MessagePort;
import top.wcpe.mc.mpmt.kernel.port.PersistencePort;
import top.wcpe.mc.mpmt.kernel.port.SchedulerPort;

/**
 * capability 功能域的 {@link Feature} 装配入口（ADR-0028 D6）。
 *
 * <p>把该域的领域逻辑（{@link PlatformCapabilityExample}）挂到运行时生命周期上：启用时从
 * {@link RuntimeContext} 取所需端口构造实例并订阅事件，停用时释放。域模块经
 * {@code META-INF/services/top.wcpe.mc.mpmt.kernel.feature.Feature} 声明本类，由
 * {@code FeatureRegistry#discover} 在启动期自动发现——平台车道无需知道任何具体域的存在。
 */
public final class CapabilityDomainFeature implements Feature {

    private PlatformCapabilityExample example;

    @Override
    public String name() {
        return "domain.capability";
    }

    @Override
    public void onEnable(RuntimeContext context) {
        Objects.requireNonNull(context, "运行时上下文不能为空");
        PlatformCapabilityExample instance = new PlatformCapabilityExample(
                context.port(PersistencePort.class),
                context.port(MessagePort.class),
                context.port(SchedulerPort.class),
                System::currentTimeMillis);
        instance.register(context.eventBus());
        this.example = instance;
    }

    @Override
    public void onDisable(RuntimeContext context) {
        // 该域的订阅随 EventBus 生命周期结束；此处只断开引用，避免停用后继续持有端口。
        this.example = null;
    }

    /** 供测试断言装配结果；未启用时为 {@code null}。 */
    PlatformCapabilityExample example() {
        return example;
    }
}
