package top.wcpe.mc.mpmt.platform.bukkit.capability;

import java.util.Objects;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/**
 * Bukkit 调度端口（L3，FR-26 / ADR-0013）：非 Folia 平台所有按归属调度统一落到服务端主线程
 * （{@code runTask}）；异步走 {@code runTaskAsynchronously}；周期任务由 {@code runTaskTimer} 驱动、
 * 句柄经 {@link BukkitTask#cancel()} 取消释放。
 *
 * <p>本实现是<b>非 Folia</b> 回退（普通 Bukkit/Spigot/Paper 单主线程模型）；Folia 区域调度由
 * {@link FoliaSchedulerPort} 承担，二者经 {@link BukkitSchedulers#create} 按 {@code FeatureGate} 选用（FR-13）。
 */
public final class BukkitSchedulerPort implements SchedulerPort {

    private final Plugin plugin;
    private final BukkitScheduler scheduler;

    public BukkitSchedulerPort(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin 不能为空");
        this.scheduler = plugin.getServer().getScheduler();
    }

    @Override
    public void runForEntity(EntityRef entity, Runnable task) {
        scheduler.runTask(plugin, task);
    }

    @Override
    public void runForLocation(WorldRef world, int x, int z, Runnable task) {
        scheduler.runTask(plugin, task);
    }

    @Override
    public void runGlobal(Runnable task) {
        scheduler.runTask(plugin, task);
    }

    @Override
    public void runAsync(Runnable task) {
        scheduler.runTaskAsynchronously(plugin, task);
    }

    @Override
    public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
        BukkitTask handle = scheduler.runTaskTimer(plugin, task, delayTicks, periodTicks);
        return handle::cancel;
    }
}
