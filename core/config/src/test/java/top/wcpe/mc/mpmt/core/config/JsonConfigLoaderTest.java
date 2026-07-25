package top.wcpe.mc.mpmt.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/** {@link JsonConfigLoader} 加载合法 / 非法 JSON 的测试。 */
class JsonConfigLoaderTest {

    private final ConfigLoader loader = new JsonConfigLoader();

    @Test
    void 加载合法JSON为类型化模型() {
        String json =
                "{\"name\":\"demo\",\"port\":25565,\"enabled\":true,"
                        + "\"tags\":[\"a\",\"b\"],"
                        + "\"nested\":{\"host\":\"localhost\",\"retry\":3}}";

        ServerConfig config = loader.load(new StringReader(json), ServerConfig.class);

        assertEquals("demo", config.name);
        assertEquals(25565, config.port);
        assertTrue(config.enabled);
        assertEquals(2, config.tags.size());
        assertEquals("b", config.tags.get(1));
        assertEquals("localhost", config.nested.host);
        assertEquals(3, config.nested.retry);
    }

    @Test
    void 非法JSON抛业务异常() {
        String broken = "{\"name\":\"demo\",,}";
        assertThrows(
                ConfigLoadException.class, () -> loader.load(new StringReader(broken), ServerConfig.class));
    }
}
