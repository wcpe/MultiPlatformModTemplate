package top.wcpe.mc.mpmt.platform.fabric.capability;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/**
 * Fabric 调度端口（L3，FR-26 / ADR-0013）：非 Folia 平台所有按归属调度统一落到服务端主线程
 * （{@code server.execute}）；异步走守护线程池；周期任务由服务端 tick 驱动、句柄可取消释放。
 *
 * <p>Folia 区域调度的真机适配经 FeatureGate 分支属后续；本实现覆盖普通 Fabric 服务端单主线程模型。
 */
public final class FabricSchedulerPort implements SchedulerPort {

    private final MinecraftServer server;
    private final ExecutorService asyncPool;
    private final List<Timer> timers = new CopyOnWriteArrayList<>();

    public FabricSchedulerPort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
        this.asyncPool =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "mpmt-async");
                            thread.setDaemon(true);
                            return thread;
                        });
        // 单一 tick 钩子驱动全部周期任务
        ServerTickEvents.END_SERVER_TICK.register(tickServer -> tickTimers());
    }

    @Override
    public void runForEntity(EntityRef entity, Runnable task) {
        server.execute(task);
    }

    @Override
    public void runForLocation(WorldRef world, int x, int z, Runnable task) {
        server.execute(task);
    }

    @Override
    public void runGlobal(Runnable task) {
        server.execute(task);
    }

    @Override
    public void runAsync(Runnable task) {
        asyncPool.execute(task);
    }

    @Override
    public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
        Timer timer = new Timer(delayTicks, periodTicks, task);
        timers.add(timer);
        return () -> timers.remove(timer);
    }

    private void tickTimers() {
        for (Timer timer : timers) {
            timer.tick();
        }
    }

    /** 周期任务：延迟 {@code delay} tick 后首次触发，之后每 {@code period} tick 触发一次。 */
    private static final class Timer {
        private final long period;
        private final Runnable task;
        private long countdown;

        Timer(long delayTicks, long periodTicks, Runnable task) {
            this.period = Math.max(periodTicks, 1L);
            this.task = task;
            this.countdown = Math.max(delayTicks, 1L);
        }

        void tick() {
            if (--countdown <= 0L) {
                countdown = period;
                task.run();
            }
        }
    }
}
