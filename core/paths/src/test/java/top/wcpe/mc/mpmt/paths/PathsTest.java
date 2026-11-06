package top.wcpe.mc.mpmt.paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.wcpe.mc.mpmt.kernel.port.DataDirectoryPort;

/**
 * {@link Paths} 的行为断言（ADR-0029：只断言产物 / 运行期行为，不读源码文本）。
 *
 * <p>两个维度的不变式：
 *
 * <ul>
 *   <li><b>定位</b>：各预设落在基目录的正确子目录下，且客户端与服务端经同一端口得到同一套位置；
 *   <li><b>不越界</b>：带目录分隔的名字、相对目录名一律拒绝——基目录是边界。
 * </ul>
 *
 * <p>注意本类与 {@code java.nio.file.Paths} 同名，且同包类优先于 import，
 * 故这里一律用全限定名引用 JDK 那个；不要加 import，加了也会被遮蔽。
 */
class PathsTest {

    /** {@return 以 {@code name} 为基目录的路径（Java 8 无 {@code Path.of}，须经 {@code Paths.get}）} */
    private static Path jdkPath(String name) {
        return java.nio.file.Paths.get(name);
    }

    /** {@return 以 {@code base} 为基目录的端口实现} */
    private static DataDirectoryPort port(Path base) {
        return () -> base;
    }

    @Test
    void 各预设目录落在基目录之下() {
        Path base = jdkPath("base");
        Paths paths = Paths.of(port(base));

        assertEquals(base, paths.baseDirectory());
        assertEquals(base.resolve("config"), paths.configDirectory());
        assertEquals(base.resolve("data"), paths.dataDirectory());
        assertEquals(base.resolve("resources"), paths.resourcesDirectory());
    }

    @Test
    void 文件名拼在对应预设目录下() {
        Path base = jdkPath("base");
        Paths paths = Paths.of(port(base));

        assertEquals(base.resolve("config").resolve("server.yml"), paths.configFile("server.yml"));
        assertEquals(base.resolve("data").resolve("stats.properties"), paths.dataFile("stats.properties"));
        assertEquals(base.resolve("resources").resolve("icon.png"), paths.resourceFile("icon.png"));
    }

    @Test
    void 只定位不创建_读路径无副作用(@TempDir Path tempDir) {
        Paths paths = Paths.of(port(tempDir));

        Path file = paths.configFile("absent.yml");

        assertTrue(Files.notExists(file), "定位不应产生文件");
        assertTrue(Files.notExists(paths.configDirectory()), "定位不应创建目录");
    }

    @Test
    void ensureParent_只在写前显式建目录(@TempDir Path tempDir) {
        Paths paths = Paths.of(port(tempDir));
        Path file = paths.dataFile("x.properties");

        Paths.ensureParent(file);

        assertTrue(Files.isDirectory(paths.dataDirectory()));
        assertTrue(Files.notExists(file), "建父目录不应顺手建文件");
    }

    @Test
    void 拒绝含目录分隔符的名字() {
        Paths paths = Paths.of(port(jdkPath("base")));

        assertThrows(IllegalArgumentException.class, () -> paths.configFile("../escape.yml"));
        assertThrows(IllegalArgumentException.class, () -> paths.dataFile("sub/x.properties"));
        assertThrows(IllegalArgumentException.class, () -> paths.resourceFile("sub\\x.png"));
    }

    @Test
    void 拒绝空名与相对目录名() {
        Paths paths = Paths.of(port(jdkPath("base")));

        assertThrows(IllegalArgumentException.class, () -> paths.configFile(""));
        assertThrows(IllegalArgumentException.class, () -> paths.dataFile("."));
        assertThrows(IllegalArgumentException.class, () -> paths.resourceFile(".."));
    }

    @Test
    void 空端口被拒() {
        assertThrows(NullPointerException.class, () -> Paths.of(null));
    }
}
