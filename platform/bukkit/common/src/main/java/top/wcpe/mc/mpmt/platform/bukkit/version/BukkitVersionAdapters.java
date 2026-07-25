package top.wcpe.mc.mpmt.platform.bukkit.version;

import java.util.Iterator;
import java.util.Objects;
import java.util.ServiceLoader;

/** 发现构建期选中的唯一 Bukkit L4 适配器。 */
public final class BukkitVersionAdapters {

    private BukkitVersionAdapters() {
        // 工具类不实例化
    }

    /**
     * 从指定类加载器发现唯一适配器。
     *
     * @throws IllegalStateException 零个或多个实现，说明产物装配错误
     */
    public static BukkitVersionAdapter load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "类加载器不能为空");
        Iterator<BukkitVersionAdapter> adapters =
                ServiceLoader.load(BukkitVersionAdapter.class, classLoader).iterator();
        if (!adapters.hasNext()) {
            throw new IllegalStateException("未发现 Bukkit L4 版本适配器");
        }
        BukkitVersionAdapter selected = adapters.next();
        if (adapters.hasNext()) {
            throw new IllegalStateException("发现多个 Bukkit L4 版本适配器，产物发生版本串扰");
        }
        return selected;
    }
}
