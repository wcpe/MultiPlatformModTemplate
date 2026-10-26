package top.wcpe.mc.mpmt.core.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import top.wcpe.mc.mpmt.kernel.feature.Feature;

/**
 * 特性注册表：在运行时启用前登记全部 {@link Feature}，保持注册顺序，按名去重。
 *
 * <p>仅供启用前的装配阶段写入；启用后视为只读（{@link MpmtRuntime} 控制时序）。
 *
 * <p>特性来源有两种：{@link #register} 显式登记，以及 {@link #discover} 经
 * {@link ServiceLoader} 从 classpath 自动发现（各功能域模块在自身
 * {@code META-INF/services/top.wcpe.mc.mpmt.kernel.feature.Feature} 中声明实现类）。
 * 后者使「新增功能域 = 新增模块 + 一份 services 文件」，无需改动任何平台车道（ADR-0028）。
 */
public final class FeatureRegistry {

    private final List<Feature> features = new ArrayList<>();
    private final Set<String> names = new LinkedHashSet<>();

    /**
     * 登记一个特性。
     *
     * @throws IllegalArgumentException 同名特性重复登记
     */
    public void register(Feature feature) {
        Objects.requireNonNull(feature, "特性不能为空");
        String name = Objects.requireNonNull(feature.name(), "特性名不能为空");
        if (!names.add(name)) {
            throw new IllegalArgumentException("特性重复登记：" + name);
        }
        features.add(feature);
    }

    /**
     * 经 {@link ServiceLoader} 自动发现并登记特性。
     *
     * <p>发现范围由 {@code classLoader} 决定：传入承载功能域模块的类加载器即可看到全部已声明域。
     * 单个 providers 实例化失败不影响其它域——失败项会被跳过并记录到返回列表由调用方决定是否告警，
     * 避免一个坏域阻断整个启动（与 {@link MpmtRuntime} 对单特性启用失败的隔离策略一致）。
     *
     * @param classLoader 用于发现的类加载器
     * @return 成功登记的域数量
     * @throws IllegalArgumentException 发现到的域与已登记域重名（属装配期配置错误，须快速失败）
     */
    public int discover(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader 不能为空");
        int registered = 0;
        for (Feature feature : ServiceLoader.load(Feature.class, classLoader)) {
            register(feature);
            registered++;
        }
        return registered;
    }

    /**
     * 宽容版自动发现：单个 provider 无法实例化时跳过，不让整个启动失败。
     *
     * <p>仅用于「域模块可选存在」的场景；核心域仍应走 {@link #discover} 以保证缺类即失败。
     *
     * @param classLoader          用于发现的类加载器
     * @param instantiationFailures 收集实例化失败的 provider（元素为可读描述）
     * @return 成功登记的域数量
     */
    public int discoverLenient(ClassLoader classLoader, List<String> instantiationFailures) {
        Objects.requireNonNull(classLoader, "classLoader 不能为空");
        Objects.requireNonNull(instantiationFailures, "instantiationFailures 不能为空");
        int registered = 0;
        ServiceLoader<Feature> loader = ServiceLoader.load(Feature.class, classLoader);
        Iterator<Feature> iterator = loader.iterator();
        while (true) {
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                register(iterator.next());
                registered++;
            } catch (ServiceConfigurationError error) {
                instantiationFailures.add(String.valueOf(error.getMessage()));
            }
        }
        return registered;
    }

    /** 按注册顺序返回全部特性（只读视图）。 */
    public List<Feature> features() {
        return Collections.unmodifiableList(new ArrayList<>(features));
    }
}
