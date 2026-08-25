package top.wcpe.mc.mpmt.core.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import top.wcpe.mc.mpmt.core.domain.event.EventBusPort;
import top.wcpe.mc.mpmt.core.domain.event.SimpleEventBus;

/**
 * 框架运行时编排：持有自有 EventBus、端口注册表、特性注册表，并驱动生命周期。
 *
 * <p>装配流程（由 L2 平台层驱动）：平台发现后把端口 {@link #ports()} 注入、特性 {@link #features()} 登记，
 * 再调用 {@link #enable()}。本类不做平台发现（L1 不依赖 L2，ADR-0001）。
 *
 * <p>生命周期单向推进 {@code NEW -> ENABLED -> DISABLED}，转换受 synchronized 守护、非法转换快速失败。
 */
public final class MpmtRuntime implements RuntimeContext {

    /** 生命周期阶段。 */
    public enum Phase {
        NEW,
        ENABLED,
        DISABLED
    }

    private final EventBusPort eventBus;
    private final RuntimePorts ports;
    private final FeatureRegistry featureRegistry;
    private volatile Phase phase = Phase.NEW;

    public MpmtRuntime() {
        this(new SimpleEventBus(), new RuntimePorts(), new FeatureRegistry());
    }

    public MpmtRuntime(EventBusPort eventBus, RuntimePorts ports, FeatureRegistry featureRegistry) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus 不能为空");
        this.ports = Objects.requireNonNull(ports, "ports 不能为空");
        this.featureRegistry = Objects.requireNonNull(featureRegistry, "featureRegistry 不能为空");
    }

    @Override
    public EventBusPort eventBus() {
        return eventBus;
    }

    @Override
    public <T> T port(Class<T> type) {
        return ports.get(type);
    }

    /** 端口注册表（装配阶段写入）。 */
    public RuntimePorts ports() {
        return ports;
    }

    /** 特性注册表（启用前登记）。 */
    public FeatureRegistry features() {
        return featureRegistry;
    }

    /** 当前生命周期阶段。 */
    public Phase phase() {
        return phase;
    }

    /**
     * 启用运行时：按注册顺序启用全部特性。
     *
     * <p>特性启用异常向上传播（启动期失败快，不静默吞掉）；只能从 {@link Phase#NEW} 进入。
     *
     * @throws IllegalStateException 不在 NEW 阶段
     */
    public synchronized void enable() {
        if (phase != Phase.NEW) {
            throw new IllegalStateException("运行时不可重复启用，当前阶段：" + phase);
        }
        List<Feature> enabled = new ArrayList<>();
        try {
            for (Feature feature : featureRegistry.features()) {
                feature.onEnable(this);
                enabled.add(feature);
            }
            phase = Phase.ENABLED;
        } catch (RuntimeException | Error failure) {
            rollbackEnabledFeatures(enabled, failure);
            throw failure;
        }
    }

    /** 启动失败时按反序释放已成功启用的特性，避免重试前遗留资源。 */
    private void rollbackEnabledFeatures(List<Feature> enabled, Throwable failure) {
        for (int i = enabled.size() - 1; i >= 0; i--) {
            try {
                enabled.get(i).onDisable(this);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    /**
     * 停用运行时：按注册逆序停用全部特性。
     *
     * @throws IllegalStateException 不在 ENABLED 阶段
     */
    public synchronized void disable() {
        if (phase != Phase.ENABLED) {
            throw new IllegalStateException("运行时未处于启用态，当前阶段：" + phase);
        }
        List<Feature> all = featureRegistry.features();
        for (int i = all.size() - 1; i >= 0; i--) {
            all.get(i).onDisable(this);
        }
        phase = Phase.DISABLED;
    }
}
