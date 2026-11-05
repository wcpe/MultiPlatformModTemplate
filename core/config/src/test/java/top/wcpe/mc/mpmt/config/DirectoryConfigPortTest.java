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

    // ── writeDefault：首次新建、已存在不改、无模板则失败 ──────────────────────

    @Test
    void writeDefault_首次新建并带上模板内容(@TempDir Path base) {
        DirectoryConfigPort port = port(base);

        assertTrue(port.writeDefault("test-default.yml"));

        assertEquals(TEMPLATE_BODY, readRaw(base, "test-default.yml"));
    }

    @Test
    void writeDefault_已存在时一字不改(@TempDir Path base) throws IOException {
        write(base, "test-default.yml", "reminderDelayTicks: 5\n");

        assertFalse(port(base).writeDefault("test-default.yml"));

        assertEquals("reminderDelayTicks: 5\n", readRaw(base, "test-default.yml"));
    }

    @Test
    void writeDefault_缺模板时报错而不是写空文件(@TempDir Path base) {
        DirectoryConfigPort port = port(base);

        assertThrows(java.io.UncheckedIOException.class, () -> port.writeDefault("no-such-template.yml"));
        assertFalse(Files.exists(base.resolve("config").resolve("no-such-template.yml")), "失败不应留下空文件");
    }

    @Test
    void writeDefault_写出的内容能被自己读回(@TempDir Path base) {
        DirectoryConfigPort port = port(base);
        port.writeDefault("test-default.yml");

        Optional<SampleConfig> loaded = port.read("test-default.yml", SampleConfig.class);

        assertTrue(loaded.isPresent());
        assertEquals(7L, loaded.get().reminderDelayTicks);
        assertEquals(99L, loaded.get().reminderPeriodTicks);
    }

    /** 测试类路径里的缺省模板内容（对应 src/test/resources/mpmt-defaults/test-default.yml）。 */
    private static final String TEMPLATE_BODY =
            "# 测试用缺省模板\nreminderDelayTicks: 7\nreminderPeriodTicks: 99\n";

    private static String readRaw(Path base, String name) {
        try {
            return new String(Files.readAllBytes(base.resolve("config").resolve(name)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
