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
 * <p>全局只注册一次 tick 钩子，再按 {@link MinecraftServer} 身份路由到活动实例，避免集成服/重载时
 * 多实例互相串 tick；{@link #close()} 释放定时器与线程池。
 *
 * <p>Folia 区域调度的真机适配经 FeatureGate 分支属后续；本实现覆盖普通 Fabric 服务端单主线程模型。
 */
@SuppressWarnings("PMD.CompareObjectsWithEquals")
public final class FabricSchedulerPort implements SchedulerPort, AutoCloseable {

    private static final List<FabricSchedulerPort> ACTIVE_PORTS = new CopyOnWriteArrayList<>();

    static {
        // 全局只注册一次 tick 钩子，再按服务端身份路由到活动实例
        ServerTickEvents.END_SERVER_TICK.register(FabricSchedulerPort::tickServer);
    }

    private final MinecraftServer server;
    private final ExecutorService asyncPool;
    private final List<Timer> timers = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public FabricSchedulerPort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
        this.asyncPool =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "mpmt-async");
                            thread.setDaemon(true);
                            return thread;
                        });
        ACTIVE_PORTS.add(this);
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
        ACTIVE_PORTS.remove(this);
        timers.clear();
        asyncPool.shutdownNow();
    }

    /** 按服务端身份驱动活动实例的周期任务（测试可直接调用）。 */
    static void tickServer(MinecraftServer server) {
        for (FabricSchedulerPort port : ACTIVE_PORTS) {
            if (port.server == server) {
                port.tickTimers();
            }
        }
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
            throw new IllegalStateException("Fabric 调度器已关闭");
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
