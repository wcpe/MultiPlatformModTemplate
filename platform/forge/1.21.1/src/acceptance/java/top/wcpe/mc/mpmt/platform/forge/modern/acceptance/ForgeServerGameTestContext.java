package top.wcpe.mc.mpmt.platform.forge.modern.acceptance;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/** 把共享验收上下文落到真实 Forge MinecraftServer。 */
public final class ForgeServerGameTestContext implements ServerGameTestContext {

    private static final long MILLIS_PER_TICK = 50L;

    private final MinecraftServer server;

    public ForgeServerGameTestContext(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "服务端不能为空");
    }

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
                    } catch (Throwable error) {
                        future.completeExceptionally(error);
                    }
                });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待服务端主线程被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("服务端主线程执行异常", cause);
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
        for (int tick = 0; tick < timeoutTicks; tick++) {
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
            throw new IllegalStateException("验收等待被中断", e);
        }
    }
}
