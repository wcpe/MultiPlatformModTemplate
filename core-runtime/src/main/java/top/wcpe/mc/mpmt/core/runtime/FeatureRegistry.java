package top.wcpe.mc.mpmt.core.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 特性注册表：在运行时启用前登记全部 {@link Feature}，保持注册顺序，按名去重。
 *
 * <p>仅供启用前的装配阶段写入；启用后视为只读（{@link MpmtRuntime} 控制时序）。
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

    /** 按注册顺序返回全部特性（只读视图）。 */
    public List<Feature> features() {
        return Collections.unmodifiableList(new ArrayList<>(features));
    }
}
