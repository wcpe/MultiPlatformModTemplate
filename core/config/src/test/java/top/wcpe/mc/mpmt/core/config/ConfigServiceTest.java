package top.wcpe.mc.mpmt.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ConfigService} 文件加载、扩展名判别、错误兜底的测试。 */
class ConfigServiceTest {

    private final ConfigService service = new ConfigService();

    @Test
    void 按yml扩展名加载文件(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.yml");
        Files.write(
                file,
                ("name: demo\nport: 25565\nenabled: false\n").getBytes(StandardCharsets.UTF_8));

        ServerConfig config = service.load(file, ServerConfig.class);

        assertEquals("demo", config.name);
        assertEquals(25565, config.port);
    }

    @Test
    void 按json扩展名加载文件(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.json");
        Files.write(
                file,
                ("{\"name\":\"demo\",\"port\":1}").getBytes(StandardCharsets.UTF_8));

        ServerConfig config = service.load(file, ServerConfig.class);

        assertEquals("demo", config.name);
        assertEquals(1, config.port);
    }

    @Test
    void 显式格式覆盖扩展名判别(@TempDir Path dir) throws IOException {
        // 文件名扩展名为 .conf（未知），但显式指定 JSON
        Path file = dir.resolve("server.conf");
        Files.write(file, ("{\"name\":\"x\"}").getBytes(StandardCharsets.UTF_8));

        ServerConfig config = service.load(file, ConfigFormat.JSON, ServerConfig.class);

        assertEquals("x", config.name);
    }

    @Test
    void 未知扩展名抛业务异常(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.conf");
        Files.write(file, ("{}").getBytes(StandardCharsets.UTF_8));

        assertThrows(ConfigLoadException.class, () -> service.load(file, ServerConfig.class));
    }

    @Test
    void 文件缺失抛业务异常(@TempDir Path dir) {
        Path missing = dir.resolve("nope.yml");
        assertThrows(ConfigLoadException.class, () -> service.load(missing, ServerConfig.class));
    }

    @Test
    void 文件内容非法抛业务异常(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad.json");
        Files.write(file, ("{,,}").getBytes(StandardCharsets.UTF_8));

        assertThrows(ConfigLoadException.class, () -> service.load(file, ServerConfig.class));
    }
}
