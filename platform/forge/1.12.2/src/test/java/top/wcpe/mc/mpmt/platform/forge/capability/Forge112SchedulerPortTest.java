package top.wcpe.mc.mpmt.platform.forge.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.junit.jupiter.api.Test;

/**
 * {@link Forge112SchedulerPort}：周期任务由服务端 tick 驱动、可取消；关服释放异步池并拒绝新任务。
 *
 * <p>服务端与 tick 事件都用 {@code Unsafe.allocateInstance} 造未初始化实例：本类只用到服务端的身份
 * （测试不调 {@code addScheduledTask}）与事件的 {@code phase} 字段。Unsafe 经反射取用
 * （不直接 import {@code sun.misc}），与同车道其它测试一致。
 */
class Forge112SchedulerPortTest {

    @Test
    void 周期任务按延迟与周期触发且可取消() throws Exception {
        Forge112SchedulerPort scheduler = new Forge112SchedulerPort(服务端());
        AtomicInteger runs = new AtomicInteger();
        try {
            AutoCloseable handle = scheduler.runTimer(2L, 3L, runs::incrementAndGet);
            assertEquals(1, timers(scheduler).size(), "注册后应挂上一个周期任务");

            tick(scheduler, 1);
            assertEquals(0, runs.get(), "未到首次延迟不得执行");
            tick(scheduler, 1);
            assertEquals(1, runs.get(), "到首次延迟应执行一次");
            tick(scheduler, 3);
            assertEquals(2, runs.get(), "之后按周期执行");

            handle.close();
            assertEquals(0, timers(scheduler).size(), "取消后应搞掉句柄");
            tick(scheduler, 10);
            assertEquals(2, runs.get(), "取消后不再执行");
        } finally {
            scheduler.close();
        }
    }

    @Test
    void START阶段不驱动周期任务() throws Exception {
        Forge112SchedulerPort scheduler = new Forge112SchedulerPort(服务端());
        AtomicInteger runs = new AtomicInteger();
        try {
            scheduler.runTimer(1L, 1L, runs::incrementAndGet);

            scheduler.onServerTick(服务端tick(TickEvent.Phase.START));

            assertEquals(0, runs.get(), "只有 END 阶段才推进计时");
        } finally {
            scheduler.close();
        }
    }

    @Test
    void 关闭后释放异步池并拒绝新任务() throws Exception {
        Forge112SchedulerPort scheduler = new Forge112SchedulerPort(服务端());

        scheduler.close();

        assertTrue(异步池(scheduler).isShutdown());
        assertThrows(IllegalStateException.class, () -> scheduler.runAsync(() -> {}));
        assertThrows(IllegalStateException.class, () -> scheduler.runGlobal(() -> {}));
        assertThrows(IllegalStateException.class, () -> scheduler.runTimer(1L, 1L, () -> {}));
        // 幂等：重复 close 不抛
        scheduler.close();
    }

    @Test
    void 服务端为空时报错() {
        assertThrows(NullPointerException.class, () -> new Forge112SchedulerPort(null));
    }

    private static void tick(Forge112SchedulerPort scheduler, int times) {
        for (int i = 0; i < times; i++) {
            scheduler.onServerTick(服务端tick(TickEvent.Phase.END));
        }
    }

    private static TickEvent.ServerTickEvent 服务端tick(TickEvent.Phase phase) {
        TickEvent.ServerTickEvent event = 未初始化(TickEvent.ServerTickEvent.class);
        设字段(event, "phase", phase);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> timers(Forge112SchedulerPort scheduler) throws Exception {
        Field field = Forge112SchedulerPort.class.getDeclaredField("timers");
        field.setAccessible(true);
        return (List<Object>) field.get(scheduler);
    }

    private static ExecutorService 异步池(Forge112SchedulerPort scheduler) throws Exception {
        Field field = Forge112SchedulerPort.class.getDeclaredField("asyncPool");
        field.setAccessible(true);
        return (ExecutorService) field.get(scheduler);
    }

    private static MinecraftServer 服务端() {
        return 未初始化(DedicatedServer.class);
    }

    private static <T> T 未初始化(Class<T> type) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法构造测试替身：" + type.getName(), error);
        }
    }

    private static void 设字段(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("无法设置字段 " + name, e);
            }
        }
        throw new IllegalStateException("找不到字段 " + name);
    }
}
