package top.wcpe.mc.mpmt.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/** {@link YamlConfigLoader} 加载合法 / 非法 YAML 的测试。 */
class YamlConfigLoaderTest {

    private final ConfigLoader loader = new YamlConfigLoader();

    @Test
    void 加载合法YAML为类型化模型() {
        String yaml =
                "name: demo\n"
                        + "port: 25565\n"
                        + "enabled: true\n"
                        + "tags:\n"
                        + "  - a\n"
                        + "  - b\n"
                        + "nested:\n"
                        + "  host: localhost\n"
                        + "  retry: 3\n";

        ServerConfig config = loader.load(new StringReader(yaml), ServerConfig.class);

        assertEquals("demo", config.name);
        assertEquals(25565, config.port);
        assertTrue(config.enabled);
        assertEquals(2, config.tags.size());
        assertEquals("a", config.tags.get(0));
        assertEquals("localhost", config.nested.host);
        assertEquals(3, config.nested.retry);
    }

    @Test
    void 非法YAML抛业务异常() {
        // 缩进 / 结构损坏的 YAML
        String broken = "name: demo\n  port: : :\n";
        assertThrows(
                ConfigLoadException.class, () -> loader.load(new StringReader(broken), ServerConfig.class));
    }
}
