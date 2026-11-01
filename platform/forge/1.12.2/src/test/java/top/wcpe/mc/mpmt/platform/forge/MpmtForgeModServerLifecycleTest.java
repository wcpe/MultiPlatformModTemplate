package top.wcpe.mc.mpmt.platform.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.kernel.port.SchedulerPort;
import top.wcpe.mc.mpmt.platform.forge.capability.Forge112CapabilityBootstrap;

/**
 * 服务端生命周期：停服时逆序停用运行时，并释放调度器（异步池 + tick 订阅）。
 *
 * <p>真服上这一步跑在 JVM shutdown hook 里，日志会被 SIGTERM 截断，故用单测把行为钉住：
 * 装配 → 启用 → 停服 → 运行时进入 DISABLED、`runtime` 字段清空、调度器已关闭。
 */
class MpmtForgeModServerLifecycleTest {

    @Test
    void 停服时停用运行时并释放调度器(@TempDir Path temp) throws Exception {
        MpmtForgeMod mod = new MpmtForgeMod();
        MpmtRuntime runtime = new MpmtRuntime();
        Forge112CapabilityBootstrap.register(服务端(), temp.toFile(), runtime);
        runtime.enable();
        设字段(mod, "runtime", runtime);

        mod.onServerStopping(未初始化(FMLServerStoppingEvent.class));

        assertEquals(MpmtRuntime.Phase.DISABLED, runtime.phase(), "停服应逆序停用全部特性");
        assertNull(取字段(mod, "runtime"), "停服后不应再持有已停用的运行时");
        SchedulerPort scheduler = runtime.ports().get(SchedulerPort.class);
        assertThrows(IllegalStateException.class, () -> scheduler.runGlobal(() -> {}), "调度器应已关闭");
    }

    @Test
    void 未装配时停服是空操作() {
        MpmtForgeMod mod = new MpmtForgeMod();

        // 服务端没启动过（runtime 为 null）时不得抛
        mod.onServerStopping(未初始化(FMLServerStoppingEvent.class));
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

    private static void 设字段(Object target, String name, Object value) throws Exception {
        Field field = MpmtForgeMod.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object 取字段(Object target, String name) throws Exception {
        Field field = MpmtForgeMod.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
