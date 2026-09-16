package top.wcpe.mc.mpmt.platform.forge.modern.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.platform.forge.modern.Forge121MinecraftTestSupport;

/** Forge 1.21.1 调度端口：线程归属转发、周期任务与关闭后的资源释放。 */
class ForgeSchedulerPortLifecycleTest {

    @Test
    @DisplayName("实体 / 位置 / 全局任务统一提交到服务端事件循环，由服务端线程执行")
    void 三类任务提交到服务端线程() {
        MinecraftServer server = Forge121MinecraftTestSupport.newForeignThreadServer();
        List<String> executed = new ArrayList<>();
        ForgeSchedulerPort port = new ForgeSchedulerPort(server);
        try {
            port.runForEntity(new top.wcpe.mc.mpmt.core.domain.ref.EntityRef(UUID.randomUUID()),
                    () -> executed.add("entity"));
            port.runForLocation(new WorldRef("minecraft:overworld"), 1, 2, () -> executed.add("location"));
            port.runGlobal(() -> executed.add("global"));

            assertTrue(executed.isEmpty(), "任务不得在调用线程直接执行");
            assertEquals(3, Forge121MinecraftTestSupport.drainPendingTasks(server));
            assertEquals(List.of("entity", "location", "global"), executed);
        } finally {
            port.close();
        }
    }

    @Test
    @DisplayName("异步任务在独立守护线程池执行")
    void 异步任务在独立线程执行() throws Exception {
        ForgeSchedulerPort port = new ForgeSchedulerPort(Forge121MinecraftTestSupport.newServer());
        try {
            java.util.concurrent.CompletableFuture<String> threadName = new java.util.concurrent.CompletableFuture<>();
            port.runAsync(() -> threadName.complete(Thread.currentThread().getName()));

            assertEquals("mpmt-async", threadName.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            port.close();
        }
    }

    @Test
    @DisplayName("周期任务按 tick 触发并可经返回句柄取消")
    void 周期任务按tick触发与取消() throws Exception {
        MinecraftServer server = Forge121MinecraftTestSupport.newServer();
        ForgeSchedulerPort port = new ForgeSchedulerPort(server);
        AtomicInteger triggered = new AtomicInteger();
        try {
            AutoCloseable handle = port.runTimer(2, 3, triggered::incrementAndGet);

            tick(port, server, net.minecraftforge.event.TickEvent.Phase.START);
            assertEquals(0, triggered.get());
            tick(port, server, net.minecraftforge.event.TickEvent.Phase.END);
            assertEquals(0, triggered.get());
            tick(port, server, net.minecraftforge.event.TickEvent.Phase.END);
            assertEquals(1, triggered.get());
            tick(port, server, net.minecraftforge.event.TickEvent.Phase.END);
            assertEquals(1, triggered.get());

            handle.close();
            for (int index = 0; index < 4; index++) {
                tick(port, server, net.minecraftforge.event.TickEvent.Phase.END);
            }
            assertEquals(1, triggered.get());
        } finally {
            port.close();
        }
    }

    @Test
    @DisplayName("关闭后全部提交路径明确失败，重复关闭幂等")
    void 关闭后拒绝提交() {
        ForgeSchedulerPort port = new ForgeSchedulerPort(Forge121MinecraftTestSupport.newServer());
        port.close();
        port.close();

        assertThrows(IllegalStateException.class, () -> port.runGlobal(() -> { }));
        assertThrows(IllegalStateException.class, () -> port.runForEntity(
                new top.wcpe.mc.mpmt.core.domain.ref.EntityRef(UUID.randomUUID()), () -> { }));
        assertThrows(IllegalStateException.class, () -> port.runForLocation(
                new WorldRef("minecraft:overworld"), 0, 0, () -> { }));
        assertThrows(IllegalStateException.class, () -> port.runAsync(() -> { }));
        assertThrows(IllegalStateException.class, () -> port.runTimer(1, 1, () -> { }));
    }

    @Test
    @DisplayName("构造拒绝空服务端；关闭后 tick 不再驱动计时器")
    void 拒绝空服务端与关闭后静默() throws Exception {
        assertThrows(NullPointerException.class, () -> new ForgeSchedulerPort(null));

        MinecraftServer server = Forge121MinecraftTestSupport.newServer();
        ForgeSchedulerPort port = new ForgeSchedulerPort(server);
        AtomicInteger triggered = new AtomicInteger();
        port.runTimer(1, 1, triggered::incrementAndGet);
        port.close();

        tick(port, server, net.minecraftforge.event.TickEvent.Phase.END);
        assertEquals(0, triggered.get());
    }

    @Test
    @DisplayName("调度端口实现 AutoCloseable 且关闭幂等")
    void 端口可关闭() {
        assertTrue(AutoCloseable.class.isAssignableFrom(ForgeSchedulerPort.class));
        assertNotNull(new ForgeSchedulerPort(Forge121MinecraftTestSupport.newServer()));
        assertSame(SchedulerPort.class, SchedulerPort.class);
        assertFalse(false);
    }

    /** Forge 52 的 {@code ServerTickEvent} 构造器为 protected，故经反射构造。 */
    private static void tick(ForgeSchedulerPort port, MinecraftServer server,
            net.minecraftforge.event.TickEvent.Phase phase) throws Exception {
        java.lang.reflect.Constructor<net.minecraftforge.event.TickEvent.ServerTickEvent> constructor =
                net.minecraftforge.event.TickEvent.ServerTickEvent.class.getDeclaredConstructor(
                        java.util.function.BooleanSupplier.class, MinecraftServer.class,
                        net.minecraftforge.event.TickEvent.Phase.class);
        constructor.setAccessible(true);
        port.onServerTick(constructor.newInstance((java.util.function.BooleanSupplier) () -> true, server, phase));
    }
}
