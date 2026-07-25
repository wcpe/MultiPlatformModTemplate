package top.wcpe.mc.mpmt.platform.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 平台启动上下文：按类型保存 L3 生命周期提供的原生对象，供同平台的 SPI 装配阶段读取。
 *
 * <p>容器本身不依赖任何平台类型；平台对象只在对应 L3 入口与实现之间流转，不进入 L0/L1。
 */
public final class PlatformAssemblyContext {

    private final Map<Class<?>, Object> values = new LinkedHashMap<>();

    /** 按声明类型登记一个启动上下文对象；同类型重复登记视为装配错误。 */
    public <T> PlatformAssemblyContext register(Class<T> type, T value) {
        Objects.requireNonNull(type, "上下文类型不能为空");
        Objects.requireNonNull(value, "上下文对象不能为空");
        if (values.containsKey(type)) {
            throw new PlatformAssemblyException("启动上下文重复注册：" + type.getName());
        }
        values.put(type, value);
        return this;
    }

    /** 取必需的启动上下文对象；缺失时启动期失败快。 */
    public <T> T get(Class<T> type) {
        return find(type)
                .orElseThrow(
                        () -> new PlatformAssemblyException("缺少平台启动上下文：" + type.getName()));
    }

    /** 查找可选的启动上下文对象。 */
    public <T> Optional<T> find(Class<T> type) {
        Objects.requireNonNull(type, "上下文类型不能为空");
        return Optional.ofNullable(values.get(type)).map(type::cast);
    }

    /** 是否已登记指定类型。 */
    public boolean contains(Class<?> type) {
        Objects.requireNonNull(type, "上下文类型不能为空");
        return values.containsKey(type);
    }
}
