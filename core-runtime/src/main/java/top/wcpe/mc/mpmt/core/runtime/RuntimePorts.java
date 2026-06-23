package top.wcpe.mc.mpmt.core.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 端口注册表：按端口接口类型持有平台注入的端口实现（类型安全的 {@code Class<T> -> T} 映射）。
 *
 * <p>由 L2 平台装配阶段 {@link #register} 写入、由 L0/L1 玩法经 {@link RuntimeContext#port} 读取。
 * 装配期一次性写入、之后只读（契合 ADR-0002「PlatformProvider 装配后只读」）；重复注册同一端口视为装配错误。
 */
public final class RuntimePorts {

    private final Map<Class<?>, Object> ports = new ConcurrentHashMap<>();

    /**
     * 注册一个端口实现。
     *
     * @throws IllegalStateException 同一端口类型重复注册
     */
    public <T> void register(Class<T> type, T implementation) {
        Objects.requireNonNull(type, "端口类型不能为空");
        Objects.requireNonNull(implementation, "端口实现不能为空");
        Object previous = ports.putIfAbsent(type, implementation);
        if (previous != null) {
            throw new IllegalStateException("端口重复注册：" + type.getName());
        }
    }

    /**
     * 取出端口实现。
     *
     * @throws IllegalStateException 端口未注册
     */
    public <T> T get(Class<T> type) {
        Objects.requireNonNull(type, "端口类型不能为空");
        Object implementation = ports.get(type);
        if (implementation == null) {
            throw new IllegalStateException("端口未注册：" + type.getName());
        }
        return type.cast(implementation);
    }

    /** 查找端口实现（不存在返回空）。 */
    public <T> Optional<T> find(Class<T> type) {
        Objects.requireNonNull(type, "端口类型不能为空");
        return Optional.ofNullable(ports.get(type)).map(type::cast);
    }

    /** 是否已注册某端口。 */
    public boolean contains(Class<?> type) {
        return ports.containsKey(type);
    }
}
