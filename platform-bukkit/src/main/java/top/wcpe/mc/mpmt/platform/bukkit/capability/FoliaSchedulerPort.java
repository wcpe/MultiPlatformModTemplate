package top.wcpe.mc.mpmt.platform.bukkit.capability;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/**
 * Folia 区域调度端口（L3，FR-13 / ADR-0013）：Folia 区域化多线程、无单一主线程，改某实体 / 方块必须落到拥有它的
 * 区域 / 实体线程，故按归属分派——{@code runForEntity}→{@code EntityScheduler}、{@code runForLocation}→坐标所属
 * {@code RegionScheduler}、{@code runGlobal}/{@code runTimer}→{@code GlobalRegionScheduler}、{@code runAsync}→
 * {@code AsyncScheduler}。
 *
 * <p><b>仅 Folia 实例化</b>：由 {@link BukkitSchedulers#create} 在 {@code FeatureGate} 探测到 {@code REGION_SCHEDULER}
 * 时选用（ADR-0019：paper-only 调度 API 一律经 FeatureGate 门控）；非 Folia 用 {@link BukkitSchedulerPort} 退化为
 * 服务端主线程。本类调度行为属实机维度，单测仅验证选用（MockBukkit 不支持 Folia 区域调度），realserver 由用户在
 * 真实 Folia 服确认（ADR-0013：调度端口须按平台写契约测试）。
 */
public final class FoliaSchedulerPort implements SchedulerPort {

    private final Plugin plugin;

    public FoliaSchedulerPort(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin 不能为空");
    }

    @Override
    public void runForEntity(EntityRef entity, Runnable task) {
        Entity bukkitEntity = Bukkit.getEntity(entity.getId());
        if (bukkitEntity != null) {
            // 落到实体所属线程；实体已被移除（retired）则丢弃（retired 回调传 null）
            bukkitEntity.getScheduler().run(plugin, scheduled -> task.run(), null);
        } else {
            // 找不到实体（已卸载 / 跨区域不可见）：退回全局线程，至少不丢任务
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        }
    }

    @Override
    public void runForLocation(WorldRef world, int x, int z, Runnable task) {
        World bukkitWorld = Bukkit.getWorld(world.getId());
        if (bukkitWorld != null) {
            // y 仅用于定位区域、对区域归属无影响，取 0
            Bukkit.getRegionScheduler().execute(plugin, new Location(bukkitWorld, x, 0, z), task);
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        }
    }

    @Override
    public void runGlobal(Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduled -> task.run());
    }

    @Override
    public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
        // Folia 全局区域定时器要求 delay/period >= 1 tick，钳到下限避免抛参数异常
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        ScheduledTask handle =
                Bukkit.getGlobalRegionScheduler()
                        .runAtFixedRate(plugin, scheduled -> task.run(), delay, period);
        // close() 取消周期任务（cancel 返回值忽略）
        return handle::cancel;
    }
}
