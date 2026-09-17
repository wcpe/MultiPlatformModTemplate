package top.wcpe.mc.mpmt.platform.forge.modern.capability;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.junit.jupiter.api.Test;

class ForgeSchedulerPortTest {

    @Test
    void 调度器在停服时可释放资源() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(ForgeSchedulerPort.class));

        ForgeSchedulerPort scheduler = new ForgeSchedulerPort(allocateServer());
        try {
            ((AutoCloseable) scheduler).close();

            assertTrue(asyncPoolOf(scheduler).isShutdown());
            assertThrows(IllegalStateException.class, () -> scheduler.runAsync(() -> {}));
        } finally {
            if (scheduler instanceof AutoCloseable) {
                ((AutoCloseable) scheduler).close();
            }
        }
    }

    private static ExecutorService asyncPoolOf(ForgeSchedulerPort scheduler) throws Exception {
        Field field = ForgeSchedulerPort.class.getDeclaredField("asyncPool");
        field.setAccessible(true);
        return (ExecutorService) field.get(scheduler);
    }

    /** 跳过构造函数造服务端替身；{@code sun.misc.Unsafe} 属内部专用 API，故经反射取用（同车道既有约定）。 */
    private static MinecraftServer allocateServer() throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return (MinecraftServer)
                unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, DedicatedServer.class);
    }
}
