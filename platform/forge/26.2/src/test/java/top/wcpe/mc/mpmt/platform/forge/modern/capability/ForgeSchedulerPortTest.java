package top.wcpe.mc.mpmt.platform.forge.modern.capability;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

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

    private static MinecraftServer allocateServer() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (MinecraftServer) unsafe.allocateInstance(DedicatedServer.class);
    }
}
