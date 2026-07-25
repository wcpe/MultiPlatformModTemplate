package top.wcpe.mc.mpmt.platform.fabric.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Fabric 调度器按服务端身份路由，并在关闭后停止执行（自 5d3d79d 迁入，适配 tip）。 */
class FabricSchedulerPortTest {

    @Test
    @DisplayName("tick 只路由当前服务端，关闭后拒绝新任务")
    void tick只路由当前服务端且关闭后停止执行() throws Exception {
        MinecraftServer firstServer = allocateServer();
        MinecraftServer secondServer = allocateServer();
        FabricSchedulerPort first = new FabricSchedulerPort(firstServer);
        FabricSchedulerPort second = new FabricSchedulerPort(secondServer);
        AtomicInteger firstRuns = new AtomicInteger();
        AtomicInteger secondRuns = new AtomicInteger();

        try {
            first.runTimer(1L, 1L, firstRuns::incrementAndGet);
            second.runTimer(1L, 1L, secondRuns::incrementAndGet);

            FabricSchedulerPort.tickServer(firstServer);
            assertEquals(1, firstRuns.get());
            assertEquals(0, secondRuns.get());

            first.close();
            FabricSchedulerPort.tickServer(firstServer);
            FabricSchedulerPort.tickServer(secondServer);
            assertEquals(1, firstRuns.get());
            assertEquals(1, secondRuns.get());
            assertThrows(
                    IllegalStateException.class,
                    () -> first.runTimer(1L, 1L, firstRuns::incrementAndGet));
        } finally {
            first.close();
            second.close();
        }
    }

    private static MinecraftServer allocateServer() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (MinecraftServer) unsafe.allocateInstance(DedicatedServer.class);
    }
}
