package top.wcpe.mc.mpmt.platform.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.kernel.port.ConfigPort;
import top.wcpe.mc.mpmt.kernel.port.SchedulerPort;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyException;

/**
 * 1.12.2 的 SPI 入口：与其它车道同构（platformId / FeatureGate / 从上下文取原生对象装配）。
 *
 * <p>装配后应注册全部 7 个端口；上下文缺项时失败快；能力探测在纯 JVM 下保守判否。
 */
class Forge112PlatformBootstrapTest {

    @Test
    void 平台标识与能力探测() {
        Forge112PlatformBootstrap bootstrap = new Forge112PlatformBootstrap();

        assertEquals("forge", bootstrap.platformId());
        assertInstanceOf(Forge112FeatureGate.class, bootstrap.featureGate());
        // 1.12.2 Forge 无 Folia；纯 JVM 测试下既非融合服也无集成服
        assertFalse(bootstrap.featureGate().supports(Capability.REGION_SCHEDULER));
        assertFalse(bootstrap.featureGate().supports(Capability.HYBRID_FORGE_BUKKIT));
    }

    @Test
    void 从上下文取服务端与配置目录并注册端口(@TempDir Path temp) throws Exception {
        MpmtRuntime runtime = new MpmtRuntime();
        PlatformAssemblyContext context =
                new PlatformAssemblyContext()
                        .register(MinecraftServer.class, 服务端())
                        .register(File.class, temp.toFile());

        new Forge112PlatformBootstrap().assemble(context, runtime);

        try {
            assertTrue(runtime.ports().contains(ConfigPort.class), "应注册 ConfigPort");
            assertTrue(runtime.ports().contains(SchedulerPort.class), "应注册 SchedulerPort");
        } finally {
            ((AutoCloseable) runtime.ports().get(SchedulerPort.class)).close();
        }
    }

    @Test
    void 上下文缺项时失败快() {
        MpmtRuntime runtime = new MpmtRuntime();
        PlatformAssemblyContext empty = new PlatformAssemblyContext();

        assertThrows(PlatformAssemblyException.class, () -> new Forge112PlatformBootstrap().assemble(empty, runtime));
    }

    private static MinecraftServer 服务端() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            return (MinecraftServer)
                    unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, DedicatedServer.class);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("造服务端实例失败", error);
        }
    }
}
