package top.wcpe.mc.mpmt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.wcpe.mc.mpmt.kernel.port.DataDirectoryPort;

/** ConfigPort 文件实现测试：目录解析、YAML/JSON 都能读、缺文件回空、非法内容报错。 */
class DirectoryConfigPortTest {

    @Test
    void YAML能从配置目录读到(@TempDir Path base) throws IOException {
        write(base, "counter.yml", "reminderDelayTicks: 7\nreminderPeriodTicks: 99\n");

        Optional<SampleConfig> loaded = port(base).read("counter.yml", SampleConfig.class);

        assertTrue(loaded.isPresent());
        assertEquals(7L, loaded.get().reminderDelayTicks);
        assertEquals(99L, loaded.get().reminderPeriodTicks);
    }

    @Test
    void JSON能从配置目录读到(@TempDir Path base) throws IOException {
        write(base, "counter.json", "{\"reminderDelayTicks\": 11, \"reminderPeriodTicks\": 222}");

        Optional<SampleConfig> loaded = port(base).read("counter.json", SampleConfig.class);

        assertTrue(loaded.isPresent());
        assertEquals(11L, loaded.get().reminderDelayTicks);
        assertEquals(222L, loaded.get().reminderPeriodTicks);
    }

    @Test
    void 文件不存在返回空(@TempDir Path base) {
        assertFalse(port(base).read("absent.yml", SampleConfig.class).isPresent());
    }

    @Test
    void 内容非法时招错(@TempDir Path base) throws IOException {
        write(base, "broken.yml", "reminderDelayTicks: 不是数字\n");

        assertThrows(ConfigLoadException.class, () -> port(base).read("broken.yml", SampleConfig.class));
    }

    @Test
    void 不支持的扩展名报错(@TempDir Path base) throws IOException {
        write(base, "counter.properties", "reminderDelayTicks=1\n");

        assertThrows(ConfigLoadException.class, () -> port(base).read("counter.properties", SampleConfig.class));
    }

    private static DirectoryConfigPort port(Path base) {
        DataDirectoryPort directory = () -> base;
        return new DirectoryConfigPort(directory);
    }

    private static void write(Path base, String name, String content) throws IOException {
        Path configDir = base.resolve("config");
        Files.createDirectories(configDir);
        Files.write(configDir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    /** 测试用配置模型（字段名即配置键）。 */
    public static final class SampleConfig {
        public long reminderDelayTicks = 20L;
        public long reminderPeriodTicks = 1200L;

        public SampleConfig() {
            // 供 snakeyaml 实例化
        }
    }
}
