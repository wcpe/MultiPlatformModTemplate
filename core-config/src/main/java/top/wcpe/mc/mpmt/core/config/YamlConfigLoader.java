package top.wcpe.mc.mpmt.core.config;

import java.io.Reader;
import java.util.Objects;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.introspector.PropertyUtils;

/**
 * YAML 加载器（snakeyaml）：以目标类型为根构造，按字段名映射（与 gson 行为一致，无需 getter/setter）。
 * 仅认目标类型、不放行任意全局标签实例化（snakeyaml 2.x 安全约束）。
 */
public final class YamlConfigLoader implements ConfigLoader {

    @Override
    public <T> T load(Reader reader, Class<T> type) {
        Objects.requireNonNull(reader, "reader 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        try {
            T value = newYaml(type).load(reader);
            if (value == null) {
                throw new ConfigLoadException("YAML 内容为空，无法加载为 " + type.getName());
            }
            return value;
        } catch (ConfigLoadException e) {
            throw e;
        } catch (RuntimeException e) {
            // snakeyaml 解析 / 构造异常（均为 RuntimeException）统一包成业务异常，不裸抛
            throw new ConfigLoadException("YAML 加载失败：" + e.getMessage(), e);
        }
    }

    /** 构造仅以目标类型为根、按字段访问映射的 Yaml。 */
    private static <T> Yaml newYaml(Class<T> type) {
        Constructor constructor = new Constructor(type, new LoaderOptions());
        PropertyUtils propertyUtils = new PropertyUtils();
        propertyUtils.setBeanAccess(BeanAccess.FIELD);
        constructor.setPropertyUtils(propertyUtils);
        return new Yaml(constructor);
    }
}
