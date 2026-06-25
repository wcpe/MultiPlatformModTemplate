package top.wcpe.mc.mpmt.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link ConfigFormat} 扩展名判别的穷举测试（含大小写、未知、无扩展名边界）。 */
class ConfigFormatTest {

    @Test
    void yml扩展名判为YAML() {
        assertEquals(ConfigFormat.YAML, ConfigFormat.fromFileName("app.yml"));
    }

    @Test
    void yaml扩展名判为YAML() {
        assertEquals(ConfigFormat.YAML, ConfigFormat.fromFileName("app.yaml"));
    }

    @Test
    void json扩展名判为JSON() {
        assertEquals(ConfigFormat.JSON, ConfigFormat.fromFileName("data.json"));
    }

    @Test
    void 扩展名判别忽略大小写() {
        assertEquals(ConfigFormat.YAML, ConfigFormat.fromFileName("APP.YML"));
        assertEquals(ConfigFormat.JSON, ConfigFormat.fromFileName("DATA.JSON"));
    }

    @Test
    void 路径中带目录也按文件名扩展名判别() {
        assertEquals(ConfigFormat.YAML, ConfigFormat.fromFileName("config/sub/app.yaml"));
    }

    @Test
    void 未知扩展名抛异常() {
        assertThrows(IllegalArgumentException.class, () -> ConfigFormat.fromFileName("app.txt"));
    }

    @Test
    void 无扩展名抛异常() {
        assertThrows(IllegalArgumentException.class, () -> ConfigFormat.fromFileName("app"));
    }

    @Test
    void 空文件名抛异常() {
        assertThrows(IllegalArgumentException.class, () -> ConfigFormat.fromFileName("  "));
    }
}
