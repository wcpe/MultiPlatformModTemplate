package top.wcpe.mc.mpmt.platform.forge.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.platform.forge.ForgeMinecraftTestSupport;

/** Forge 调度端口：三条线程归属转发、周期任务与关闭后的资源释放。 */
class ForgeSchedulerPortLifecycleTest {

    @Test
    @DisplayName("实体 / 位置 / 全局任务统一提交到服务端事件循环，由服务端线程执行")
    void 三类任务提交到服务端线程() {
        net.minecraft.server.MinecraftServer server = ForgeMinecraftTestSupport.newForeignThreadServer();
        List<String> executed = new ArrayList<>();
        ForgeSchedulerPort port = new ForgeSchedulerPort(server);
        try {
            port.runForEntity(new top.wcpe.mc.mpmt.core.domain.ref.EntityRef(UUID.randomUUID()),
                    () -> executed.add("entity"));
            port.runForLocation(new WorldRef("minecraft:overworld"), 1, 2, () -> executed.add("location"));
            port.runGlobal(() -> executed.add("global"));

            assertTrue(executed.isEmpty(), "任务不得在调用线程直接执行");
            assertEquals(3, ForgeMinecraftTestSupport.drainPendingTasks(server));
            assertEquals(List.of("entity", "location", "global"), executed);
        } finally {
            port.close();
        }
    }

    @Test
    @DisplayName("异步任务在独立守护线程池执行")
    void 异步任务在独立线程执行() throws Exception {
        ForgeSchedulerPort port = new ForgeSchedulerPort(ForgeMinecraftTestSupport.newServer());
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
        net.minecraft.server.MinecraftServer server = ForgeMinecraftTestSupport.newServer();
        ForgeSchedulerPort port = new ForgeSchedulerPort(server);
        java.util.concurrent.atomic.AtomicInteger triggered = new java.util.concurrent.atomic.AtomicInteger();
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
            tick(port, server, net.minecraftforge.event.TickEvent.Phase.END);
            tick(port, server, net.minecraftforge.event.TickEvent.Phase.END);
            assertEquals(2, triggered.get());

            handle.close();
            tick(port, server, net.minecraftforge.event.TickEvent.Phase.END);
            tick(port, server, net.minecraftforge.event.TickEvent.Phase.END);
            tick(port, server, net.minecraftforge.event.TickEvent.Phase.END);
            assertEquals(2, triggered.get());
        } finally {
            port.close();
        }
    }

    @Test
    @DisplayName("关闭后全部提交路径明确失败，重复关闭幂等")
    void 关闭后拒绝提交() {
        ForgeSchedulerPort port = new ForgeSchedulerPort(ForgeMinecraftTestSupport.newServer());
        port.close();
        port.close();

        assertThrows(IllegalStateException.class, () -> port.runGlobal(() -> { }));
        assertThrows(IllegalStateException.class,
                () -> port.runForEntity(new top.wcpe.mc.mpmt.core.domain.ref.EntityRef(UUID.randomUUID()), () -> { }));
        assertThrows(IllegalStateException.class,
                () -> port.runForLocation(new WorldRef("minecraft:overworld"), 0, 0, () -> { }));
        assertThrows(IllegalStateException.class, () -> port.runAsync(() -> { }));
        assertThrows(IllegalStateException.class, () -> port.runTimer(1, 1, () -> { }));
    }

    @Test
    @DisplayName("构造拒绝空服务端，关闭后 tick 不再驱动计时器")
    void 拒绝空服务端与关闭后静默() throws Exception {
        assertThrows(NullPointerException.class, () -> new ForgeSchedulerPort(null));

        net.minecraft.server.MinecraftServer server = ForgeMinecraftTestSupport.newServer();
        ForgeSchedulerPort port = new ForgeSchedulerPort(server);
        java.util.concurrent.atomic.AtomicInteger triggered = new java.util.concurrent.atomic.AtomicInteger();
        port.runTimer(1, 1, triggered::incrementAndGet);
        port.close();

        tick(port, server, net.minecraftforge.event.TickEvent.Phase.END);
        assertEquals(0, triggered.get());
        assertNotNull(port);
    }

    private static void tick(
            ForgeSchedulerPort port,
            net.minecraft.server.MinecraftServer server,
            net.minecraftforge.event.TickEvent.Phase phase) throws Exception {
        port.onServerTick(new net.minecraftforge.event.TickEvent.ServerTickEvent(
                phase, () -> true, server));
    }
}
