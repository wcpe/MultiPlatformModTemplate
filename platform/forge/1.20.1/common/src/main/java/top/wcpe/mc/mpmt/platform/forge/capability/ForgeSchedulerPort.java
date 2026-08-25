package top.wcpe.mc.mpmt.platform.forge.capability;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/** Forge 调度端口：实体、位置和全局任务统一调度到服务端线程，周期任务由服务端 tick 驱动。 */
public final class ForgeSchedulerPort implements SchedulerPort, AutoCloseable {

    private final MinecraftServer server;
    private final ExecutorService asyncPool;
    private final List<Timer> timers = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public ForgeSchedulerPort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
        this.asyncPool =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "mpmt-async");
                            thread.setDaemon(true);
                            return thread;
                        });
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void runForEntity(EntityRef entity, Runnable task) {
        ensureOpen();
        server.execute(task);
    }

    @Override
    public void runForLocation(WorldRef world, int x, int z, Runnable task) {
        ensureOpen();
        server.execute(task);
    }

    @Override
    public void runGlobal(Runnable task) {
        ensureOpen();
        server.execute(task);
    }

    @Override
    public void runAsync(Runnable task) {
        ensureOpen();
        asyncPool.execute(task);
    }

    @Override
    public synchronized AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
        ensureOpen();
        Timer timer = new Timer(delayTicks, periodTicks, task);
        timers.add(timer);
        return () -> timers.remove(timer);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        MinecraftForge.EVENT_BUS.unregister(this);
        timers.clear();
        asyncPool.shutdownNow();
    }

    /** 服务端 tick 末驱动全部周期任务。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickTimers();
    }

    private synchronized void tickTimers() {
        if (closed) {
            return;
        }
        for (Timer timer : timers) {
            timer.tick();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Forge 调度器已关闭");
        }
    }

    /** 可取消周期任务的内部计时状态。 */
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
