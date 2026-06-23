package top.wcpe.mc.mpmt.platform.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * 平台发现：经 {@link ServiceLoader} 扫描已注册的 {@link PlatformBootstrap}，校验"恰好一个活跃平台"。
 *
 * <p>零平台或多入口同时激活均启动期失败快（ADR-0002 / ADR-0008）。注意必须显式传入目标 ClassLoader——
 * Fabric/Forge/NeoForge 隔离 mod 类加载器与 Paper PluginClassLoader 下，默认线程上下文 ClassLoader 未必扫到本 jar 的 services。
 *
 * <p>说明：跨隔离类加载器的"多入口"探测（如融合服上既装 Bukkit 又装 Forge）需平台级共享存储，属 L3 范畴；
 * 本层只做同一 ClassLoader 视图内的计数失败快。
 */
public final class PlatformAssembler {

    private PlatformAssembler() {
        // 工具类不实例化
    }

    /** 经 ServiceLoader 发现并选出唯一活跃平台。 */
    public static PlatformBootstrap discover(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader 不能为空");
        List<PlatformBootstrap> found = new ArrayList<>();
        for (PlatformBootstrap bootstrap : ServiceLoader.load(PlatformBootstrap.class, classLoader)) {
            found.add(bootstrap);
        }
        return selectActive(found);
    }

    /** 从发现结果中选出唯一活跃平台；零个或多个均失败快。 */
    static PlatformBootstrap selectActive(List<PlatformBootstrap> found) {
        Objects.requireNonNull(found, "found 不能为空");
        if (found.isEmpty()) {
            throw new PlatformAssemblyException("未发现任何平台入口（PlatformBootstrap）：请确认平台胶水已注册 META-INF/services");
        }
        if (found.size() > 1) {
            throw new PlatformAssemblyException("发现多个平台入口同时激活，每进程只能有一个活跃平台：" + platformIds(found));
        }
        return found.get(0);
    }

    private static String platformIds(List<PlatformBootstrap> found) {
        StringBuilder builder = new StringBuilder();
        for (PlatformBootstrap bootstrap : found) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(bootstrap.platformId());
        }
        return builder.toString();
    }
}
