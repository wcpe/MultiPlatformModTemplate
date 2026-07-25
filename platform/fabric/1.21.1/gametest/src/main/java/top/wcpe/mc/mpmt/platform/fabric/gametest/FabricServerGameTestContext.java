package top.wcpe.mc.mpmt.platform.fabric.gametest;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/**
 * {@link ServerGameTestContext} 的 Fabric 实现（仅 gametest 接入层）：把"切主线程 / 等 tick / 轮询"
 * 落到真实 {@link MinecraftServer}。
 *
 * <p>{@link #onMain} 经 {@code server.execute} 投递到服务端主线程并阻塞驱动线程等结果（驱动线程 ≠ 主线程，
 * 不会自死锁）。世界访问由场景在 {@code onMain} 块内经 {@link #server()} 自取（场景属 Fabric 接入层、可见 MC）。
 */
public final class FabricServerGameTestContext implements ServerGameTestContext {

    private static final long MILLIS_PER_TICK = 50L;

    private final MinecraftServer server;

    public FabricServerGameTestContext(MinecraftServer server) {
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
