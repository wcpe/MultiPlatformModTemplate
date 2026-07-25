package top.wcpe.mc.mpmt.platform.bukkit.version.modern;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.platform.bukkit.capability.BukkitSchedulerPort;
import top.wcpe.mc.mpmt.platform.bukkit.capability.FoliaSchedulerPort;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitChannels;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter;

/**
 * 现代 Paper L4 公共适配：命名空间产品通道与可选 Folia 调度。
 *
 * <p>FoliaSchedulerPort 仅存在于 main 源集，由 build 在 1.12 车道 exclude；
 * 现代车道（1.20/1.21）将本类与 Folia 类一并打入产物。
 */
public abstract class ModernBukkitVersionAdapter implements BukkitVersionAdapter {

    private static final String FOLIA_MARKER =
            "io.papermc.paper.threadedregions.RegionizedServer";
    private static final BukkitChannels CHANNELS = new BukkitChannels("mpmt:main");

    @Override
    public final BukkitChannels channels() {
        return CHANNELS;
    }

    @Override
    public final SchedulerPort createScheduler(Plugin plugin, boolean regionScheduler) {
        if (regionScheduler) {
            return new FoliaSchedulerPort(plugin);
        }
        return new BukkitSchedulerPort(plugin);
    }

    @Override
    public final void executeGlobal(Plugin plugin, Runnable task) {
        Objects.requireNonNull(plugin, "plugin 不能为空");
        Objects.requireNonNull(task, "任务不能为空");
        if (classPresent(FOLIA_MARKER)) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, ModernBukkitVersionAdapter.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
