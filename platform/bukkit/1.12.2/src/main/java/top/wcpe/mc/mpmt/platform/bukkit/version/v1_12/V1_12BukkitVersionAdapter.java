package top.wcpe.mc.mpmt.platform.bukkit.version.v1_12;

import java.util.Objects;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.platform.bukkit.capability.BukkitSchedulerPort;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitChannels;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter;
import top.wcpe.mc.mpmt.platform.bukkit.version.SupportedVersion;

/** Bukkit 1.12.2 L4：旧式产品通道与传统主线程调度。 */
public final class V1_12BukkitVersionAdapter implements BukkitVersionAdapter {

    private static final BukkitChannels CHANNELS = new BukkitChannels("MPMT");

    @Override
    public SupportedVersion version() {
        return SupportedVersion.V1_12;
    }

    @Override
    public BukkitChannels channels() {
        return CHANNELS;
    }

    @Override
    public SchedulerPort createScheduler(Plugin plugin, boolean regionScheduler) {
        // 1.12 无区域调度；忽略 capability 位，始终主线程
        return new BukkitSchedulerPort(plugin);
    }

    @Override
    public void executeGlobal(Plugin plugin, Runnable task) {
        Objects.requireNonNull(plugin, "plugin 不能为空");
        plugin.getServer().getScheduler().runTask(plugin, Objects.requireNonNull(task, "任务不能为空"));
    }
}
