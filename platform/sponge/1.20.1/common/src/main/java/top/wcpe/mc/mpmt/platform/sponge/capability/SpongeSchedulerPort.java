package top.wcpe.mc.mpmt.platform.sponge.capability;

import java.util.Objects;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.scheduler.ScheduledTask;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.util.Ticks;
import org.spongepowered.plugin.PluginContainer;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/**
 * Sponge 调度端口（L3，FR-26 / ADR-0013）：Sponge 为单一服务端主线程模型，所有按归属调度统一落到
 * 服务端同步调度器（{@link org.spongepowered.api.Server#scheduler()}）；异步走 {@code asyncScheduler}；
 * 周期任务由 {@code interval} 驱动、句柄经 {@link ScheduledTask#cancel()} 取消释放。
 *
 * <p>Sponge 无 Folia 式分区调度，故不区分 entity/location/global——均提交到服务端主线程。
 */
public final class SpongeSchedulerPort implements SchedulerPort {

    private final PluginContainer plugin;

    public SpongeSchedulerPort(PluginContainer plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin 不能为空");
    }

    @Override
    public void runForEntity(EntityRef entity, Runnable task) {
        submitSync(task);
    }

    @Override
    public void runForLocation(WorldRef world, int x, int z, Runnable task) {
        submitSync(task);
    }

    @Override
    public void runGlobal(Runnable task) {
        submitSync(task);
    }

    @Override
    public void runAsync(Runnable task) {
        Sponge.asyncScheduler().submit(Task.builder().execute(task).plugin(plugin).build());
    }

    @Override
    public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
        ScheduledTask handle =
                Sponge.server()
                        .scheduler()
                        .submit(
                                Task.builder()
                                        .execute(task)
                                        .plugin(plugin)
                                        .delay(Ticks.of(delayTicks))
                                        .interval(Ticks.of(periodTicks))
                                        .build());
        return handle::cancel;
    }

    /** 提交一次性任务到服务端主线程（同步调度器）。 */
    private void submitSync(Runnable task) {
        Sponge.server().scheduler().submit(Task.builder().execute(task).plugin(plugin).build());
    }
}
