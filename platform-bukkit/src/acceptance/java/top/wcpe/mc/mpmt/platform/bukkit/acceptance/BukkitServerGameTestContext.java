package top.wcpe.mc.mpmt.platform.bukkit.acceptance;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/**
 * Bukkit 服务端验收上下文（realserver harness，ADR-0014）：把平台无关 {@link ServerGameTestContext}
 * 适配到 Bukkit 调度器。{@code onMain} 经 {@code BukkitScheduler.runTask} 切服务端主线程同步执行。
 *
 * <p><b>线程契约</b>：场景在<b>驱动线程</b>运行（非主线程），故 {@code onMain} 用 {@code CompletableFuture.get()}
 * 阻塞等主线程回填不会自死锁——若在主线程调本方法会自死锁（与 Fabric 一致）。
 */
public final class BukkitServerGameTestContext implements ServerGameTestContext {

    /** Minecraft 一个 tick 的毫秒数。 */
    private static final long MILLIS_PER_TICK = 50L;

    /**
     * 是否 Folia（区域化多线程、无全局主线程调度器）：探测标志类，与产品 {@code FeatureGate.REGION_SCHEDULER} 同源。
     * Folia 上 {@code BukkitScheduler.runTask} 抛 {@code UnsupportedOperationException}，onMain 须改走全局区域调度器。
     */
    private static final boolean FOLIA = detectFolia();

    private final Plugin plugin;

    public BukkitServerGameTestContext(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin 不能为空");
    }

    /** 取服务端（供场景在 onMain 块内访问在线玩家等）。 */
    public Server server() {
        return plugin.getServer();
    }

    /** 取插件（供场景发插件消息）。 */
    public Plugin plugin() {
        return plugin;
    }

    /** 探测 Folia：标志类存在即 Folia（区域化调度），与 BukkitSchedulers/FeatureGate 同源判据。 */
    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public <T> T onMain(Supplier<T> block) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable body =
                () -> {
                    try {
                        future.complete(block.get());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                };
        if (FOLIA) {
            // Folia 无全局主线程；onMain 的"服务端级 / 全局"语义落到全局区域线程
            // （BukkitScheduler.runTask 在 Folia 抛 UnsupportedOperationException）。
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> body.run());
        } else {
            plugin.getServer().getScheduler().runTask(plugin, body);
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("主线程执行被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("主线程执行异常", cause);
        }
    }

    @Override
    public void onMain(Runnable block) {
        onMain(
                () -> {
                    block.run();
                    return null;
                });
    }

    @Override
    public void waitTicks(int ticks) {
        try {
            Thread.sleep(ticks * MILLIS_PER_TICK);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 tick 被中断", e);
        }
    }

    @Override
    public boolean awaitUntil(int timeoutTicks, BooleanSupplier predicate) {
        for (int i = 0; i < timeoutTicks; i++) {
            if (onMain(predicate::getAsBoolean)) {
                return true;
            }
            try {
                Thread.sleep(MILLIS_PER_TICK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
