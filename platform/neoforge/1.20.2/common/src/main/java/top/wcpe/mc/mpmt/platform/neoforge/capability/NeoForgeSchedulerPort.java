package top.wcpe.mc.mpmt.platform.neoforge.capability;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/**
 * NeoForge 调度端口（L3，FR-26 / ADR-0013）：非 Folia 平台所有按归属调度统一落到服务端主线程
 * （{@code server.execute}）；异步走守护线程池；周期任务由服务端 tick（{@link TickEvent.ServerTickEvent}
 * 的 {@code END} 阶段）驱动、句柄可取消释放。
 *
 * <p>NeoForge 无 Folia，按归属方法（runForEntity / runForLocation / runGlobal）全部收敛到主线程，
 * 与 Fabric 非 Folia 路径一致。NeoForge 20.2 周期 tick 仍用 Forge 系 {@link TickEvent}（拆分的
 * {@code ServerTickEvent.Pre/Post} 属 20.4+），经 {@link NeoForge#EVENT_BUS} 订阅。
 */
public final class NeoForgeSchedulerPort implements SchedulerPort {

    private final MinecraftServer server;
    private final ExecutorService asyncPool;
    private final List<Timer> timers = new CopyOnWriteArrayList<>();

    public NeoForgeSchedulerPort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
        this.asyncPool =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "mpmt-async");
                            thread.setDaemon(true);
                            return thread;
                        });
        // 单一 tick 钩子驱动全部周期任务（订阅游戏事件总线）
        NeoForge.EVENT_BUS.register(this);
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

    /** 服务端 tick 末驱动全部周期任务（仅 {@code END} 阶段，避免一 tick 触发两次）。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
