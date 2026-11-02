package top.wcpe.mc.mpmt.platform.forge.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.wcpe.mc.mpmt.config.DirectoryConfigPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.kernel.port.ConfigPort;
import top.wcpe.mc.mpmt.kernel.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.kernel.port.MessagePort;
import top.wcpe.mc.mpmt.kernel.port.PersistencePort;
import top.wcpe.mc.mpmt.kernel.port.PlayerPort;
import top.wcpe.mc.mpmt.kernel.port.SchedulerPort;
import top.wcpe.mc.mpmt.kernel.port.WorldPort;

/**
 * {@link Forge112CapabilityBootstrap}：七个端口全部注册、配置经 {@link ConfigPort} 真能从
 * {@code <配置目录>/mpmt/config/} 读到，且装配本身不启用运行时（启用是入口的职责）。
 */
class Forge112CapabilityBootstrapTest {

    @Test
    void 装配注册七个端口且配置从平台目录读到(@TempDir Path temp) throws Exception {
        MpmtRuntime runtime = new MpmtRuntime();
        Path configFile = temp.resolve("mpmt").resolve("config").resolve("counter.yml");
        Files.createDirectories(configFile.getParent());
        Files.write(configFile, "reminderDelayTicks: 7\n".getBytes(StandardCharsets.UTF_8));

        Forge112CapabilityBootstrap.register(服务端(), temp.toFile(), runtime);

        try {
            assertInstanceOf(Forge112DataDirectoryPort.class, runtime.ports().get(DataDirectoryPort.class));
            assertInstanceOf(Forge112PersistencePort.class, runtime.ports().get(PersistencePort.class));
            assertInstanceOf(DirectoryConfigPort.class, runtime.ports().get(ConfigPort.class));
            assertInstanceOf(Forge112MessagePort.class, runtime.ports().get(MessagePort.class));
            assertInstanceOf(Forge112PlayerPort.class, runtime.ports().get(PlayerPort.class));
            assertInstanceOf(Forge112WorldPort.class, runtime.ports().get(WorldPort.class));
            assertInstanceOf(Forge112SchedulerPort.class, runtime.ports().get(SchedulerPort.class));

            Optional<SampleConfig> loaded =
                    runtime.ports().get(ConfigPort.class).read("counter.yml", SampleConfig.class);

            assertTrue(loaded.isPresent(), "配置文件在平台目录下，应能读到");
            assertEquals(7L, loaded.get().reminderDelayTicks());
            assertEquals(
                    MpmtRuntime.Phase.NEW, runtime.phase(), "装配只登记端口与特性，启用由入口在服务端启动时调");
        } finally {
            ((AutoCloseable) runtime.ports().get(SchedulerPort.class)).close();
        }
    }

    @Test
    void 参数为空时报错(@TempDir Path temp) {
        MpmtRuntime runtime = new MpmtRuntime();

        assertThrows(
                NullPointerException.class,
                () -> Forge112CapabilityBootstrap.register(null, temp.toFile(), runtime));
        assertThrows(
                NullPointerException.class,
                () -> Forge112CapabilityBootstrap.register(服务端OrNull(), null, runtime));
        assertThrows(
                NullPointerException.class,
                () -> Forge112CapabilityBootstrap.register(服务端OrNull(), temp.toFile(), null));
    }

    private static MinecraftServer 服务端OrNull() {
        return 服务端();
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

    /** 测试用配置模型：snakeyaml 按字段名映射，不需要 setter。 */
    public static final class SampleConfig {

        private long reminderDelayTicks = -1L;

        public long reminderDelayTicks() {
            return reminderDelayTicks;
        }
    }
}
