package top.wcpe.mc.mpmt.platform.forge.modern.capability;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/** Forge 调度端口：实体、位置和全局任务统一调度到服务端线程，周期任务由服务端 tick 驱动。 */
public final class ForgeSchedulerPort implements SchedulerPort {

    private final MinecraftServer server;
    private final ExecutorService asyncPool;
    private final List<Timer> timers = new CopyOnWriteArrayList<>();

    public ForgeSchedulerPort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
        this.asyncPool =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "mpmt-async");
                            thread.setDaemon(true);
                            return thread;
                        });
        // EventBus 7 拒绝仅含单个监听器的类走 register(obj)，须直接注册到该事件的 BUS
        TickEvent.ServerTickEvent.Post.BUS.addListener(this::onServerTick);
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

    /** 服务端 tick 末驱动全部周期任务。 */
    private void onServerTick(TickEvent.ServerTickEvent.Post event) {
        // 26.2 取消 phase 字段，tick 末尾由独立的 Post 事件类型表达
        for (Timer timer : timers) {
            timer.tick();
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
