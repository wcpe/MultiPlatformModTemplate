package top.wcpe.mc.mpmt.platform.bukkit.acceptance;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * 验收侧全局线程投递：Folia 用 GlobalRegionScheduler（反射，避免 1.12 编译期依赖 Paper API），
 * 否则用 BukkitScheduler.runTask。
 */
final class AcceptanceGlobalRun {

    private static final boolean FOLIA = detectFolia();

    private AcceptanceGlobalRun() {
        // 工具类
    }

    static boolean isFolia() {
        return FOLIA;
    }

    /** 在服务端“全局”语义线程上执行任务。 */
    static void run(Plugin plugin, Runnable body) {
        Objects.requireNonNull(plugin, "plugin 不能为空");
        Objects.requireNonNull(body, "任务不能为空");
        if (FOLIA) {
            runOnGlobalRegion(plugin, body);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, body);
    }

    private static void runOnGlobalRegion(Plugin plugin, Runnable body) {
        try {
            Method getter = Bukkit.class.getMethod("getGlobalRegionScheduler");
            Object scheduler = getter.invoke(null);
            // void run(Plugin, Consumer<ScheduledTask>)
            Method run =
                    scheduler
                            .getClass()
                            .getMethod("run", Plugin.class, Consumer.class);
            run.invoke(scheduler, plugin, (Consumer<Object>) scheduled -> body.run());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Folia 全局区域调度反射调用失败", e);
        }
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
