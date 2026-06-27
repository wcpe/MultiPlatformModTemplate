package top.wcpe.mc.mpmt.platform.neoforge.acceptance;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/**
 * NeoForge 服务端验收上下文（realserver harness，ADR-0014）：把平台无关 {@link ServerGameTestContext}
 * 适配到真实 {@link MinecraftServer}。{@code onMain} 经 {@code MinecraftServer.execute} 切服务端主线程同步执行。
 *
 * <p><b>线程契约</b>：场景在<b>驱动线程</b>运行（非主线程），故 {@code onMain} 用 {@code CompletableFuture.get()}
 * 阻塞等主线程回填不会自死锁——若在主线程调本方法会自死锁（与 Forge/Fabric/Bukkit 一致）。
 */
public final class NeoForgeServerGameTestContext implements ServerGameTestContext {

    /** Minecraft 一个 tick 的毫秒数。 */
    private static final long MILLIS_PER_TICK = 50L;

    private final MinecraftServer server;

    public NeoForgeServerGameTestContext(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    /** 真实服务端（供场景在 onMain 块内访问世界 / 玩家）。 */
    public MinecraftServer server() {
        return server;
    }

    @Override
    public <T> T onMain(Supplier<T> block) {
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(
                () -> {
                    try {
                        future.complete(block.get());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等主线程执行被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
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
        sleep(ticks * MILLIS_PER_TICK);
    }

    @Override
    public boolean awaitUntil(int timeoutTicks, BooleanSupplier predicate) {
        for (int i = 0; i < timeoutTicks; i++) {
            if (onMain(predicate::getAsBoolean)) {
                return true;
            }
            sleep(MILLIS_PER_TICK);
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}
