package top.wcpe.mc.mpmt.platform.bukkit;

import org.bukkit.plugin.java.JavaPlugin;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * Bukkit 家族插件入口：进服后驱动平台装配——构造运行时、经本插件类加载器发现并装配唯一活跃平台、启用特性。
 *
 * <p>用本插件类加载器（PluginClassLoader）做 ServiceLoader 发现，确保扫到本 jar 内的 services（ADR-0002 注意项）。
 */
public class MpmtBukkitPlugin extends JavaPlugin {

    private MpmtRuntime runtime;

    @Override
    public void onEnable() {
        runtime = new MpmtRuntime();
        // 玩法特性随后续增量登记到 runtime.features()；当前先打通"发现 + 装配 + 启用"链路
        PlatformProvider.boot(getClass().getClassLoader(), runtime);
        runtime.enable();
        getLogger().info("MPMT 已装配并启用，活跃平台：" + PlatformProvider.get().platformId());
    }

    @Override
    public void onDisable() {
        if (runtime != null && runtime.phase() == MpmtRuntime.Phase.ENABLED) {
            runtime.disable();
        }
    }
}
