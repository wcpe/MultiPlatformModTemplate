package top.wcpe.mc.mpmt.core.paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 资源路径预设穷举（FR-30 / ADR-0010）：预设位置、相对名解析、越界拒绝、失败快。 */
class ResourcePathsTest {

    private static final Path BASE =
            Paths.get(System.getProperty("java.io.tmpdir")).resolve("mpmt-base").normalize();

    private static ResourcePaths paths() {
        // DataDirectoryPort 为函数式接口，平台提供基目录；此处用固定基目录做纯路径逻辑测试
        return new ResourcePaths(() -> BASE);
    }

    @Test
    @DisplayName("基目录与三个预设目录相对基目录解析")
    void 预设目录() {
        ResourcePaths p = paths();
        assertEquals(BASE, p.baseDirectory());
        assertEquals(BASE.resolve("config"), p.configDirectory());
        assertEquals(BASE.resolve("data"), p.dataDirectory());
        assertEquals(BASE.resolve("resources"), p.resourcesDirectory());
    }

    @Test
    @DisplayName("相对名解析到对应预设目录下")
    void 相对名解析() {
        ResourcePaths p = paths();
        assertEquals(BASE.resolve("config").resolve("app.yml"), p.configFile("app.yml"));
        assertEquals(BASE.resolve("data").resolve("players.json"), p.dataFile("players.json"));
        assertEquals(BASE.resolve("resources").resolve("lang.json"), p.resourceFile("lang.json"));
    }

    @Test
    @DisplayName("嵌套相对名仍落在预设目录内")
    void 嵌套相对名() {
        ResourcePaths p = paths();
        Path nested = p.configFile("sub/dir/app.yml");
        assertEquals(BASE.resolve("config").resolve("sub").resolve("dir").resolve("app.yml"), nested);
        assertTrue(nested.startsWith(p.configDirectory()));
    }

    @Test
    @DisplayName("越界相对名（../ 逃逸预设目录）被拒")
    void 越界被拒() {
        ResourcePaths p = paths();
        assertThrows(IllegalArgumentException.class, () -> p.configFile("../escape.yml"));
        assertThrows(IllegalArgumentException.class, () -> p.dataFile("../../outside.json"));
    }

    @Test
    @DisplayName("绝对路径相对名被拒（不得逃逸基目录）")
    void 绝对路径被拒() {
        ResourcePaths p = paths();
        // 用文件系统根下、明确在基目录之外的绝对路径，直观证明「绝对路径逃逸基目录」被拦
        String absolute = BASE.getRoot().resolve("etc").resolve("secret.yml").toString();
        assertThrows(IllegalArgumentException.class, () -> p.resourceFile(absolute));
    }

    @Test
    @DisplayName("空 / 空白相对名被拒")
    void 空相对名被拒() {
        ResourcePaths p = paths();
        assertThrows(NullPointerException.class, () -> p.configFile(null));
        assertThrows(IllegalArgumentException.class, () -> p.configFile("   "));
    }

    @Test
    @DisplayName("平台基目录为空时失败快")
    void 基目录为空失败快() {
        ResourcePaths p = new ResourcePaths(() -> null);
        assertThrows(IllegalStateException.class, p::configDirectory);
    }

    @Test
    @DisplayName("构造端口为空即拒")
    void 端口为空() {
        assertThrows(NullPointerException.class, () -> new ResourcePaths(null));
    }
}
