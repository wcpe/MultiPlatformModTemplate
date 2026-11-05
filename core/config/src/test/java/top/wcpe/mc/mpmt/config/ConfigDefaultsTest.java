package top.wcpe.mc.mpmt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.wcpe.mc.mpmt.kernel.port.ConfigPort;

/**
 * {@link ConfigDefaults} 的行为断言（ADR-0029：只断言运行期行为）。
 *
 * <p>要验证的核心不变式：
 *
 * <ul>
 *   <li><b>清单解析</b>：注释与空行被忽略，重复项只取一次；
 *   <li><b>幂等</b>：已存在的文件不被覆盖（不能拿默认值盖掉用户改的配置）；
 *   <li><b>失败隔离</b>：单份配置写失败不影响其它——缺一块配置不该让服务起不来。
 * </ul>
 */
class ConfigDefaultsTest {

    @Test
    void 没有清单时什么也不做(@TempDir Path tempDir) throws IOException {
        RecordingConfigPort port = new RecordingConfigPort();

        List<String> written = ConfigDefaults.writeAll(port, loaderWith(tempDir));

        assertTrue(written.isEmpty());
        assertTrue(port.written.isEmpty());
    }

    @Test
    void 清单里的文件名会被逐个写入(@TempDir Path tempDir) throws IOException {
        addManifest(tempDir, "alpha.yml\nbeta.yml\n");
        RecordingConfigPort port = new RecordingConfigPort();

        List<String> written = ConfigDefaults.writeAll(port, loaderWith(tempDir));

        assertEquals(java.util.Arrays.asList("alpha.yml", "beta.yml"), written);
        assertEquals(java.util.Arrays.asList("alpha.yml", "beta.yml"), port.written);
    }

    @Test
    void 清单里的注释与空行被忽略(@TempDir Path tempDir) throws IOException {
        addManifest(tempDir, "# 这是注释\n\nalpha.yml\n   \nbeta.yml\n");

        List<String> names = ConfigDefaults.declaredFileNames(loaderWith(tempDir));

        assertEquals(java.util.Arrays.asList("alpha.yml", "beta.yml"), names);
    }

    @Test
    void 重复声明的文件名只写一次(@TempDir Path tempDir) throws IOException {
        addManifest(tempDir, "alpha.yml\nalpha.yml\n");
        RecordingConfigPort port = new RecordingConfigPort();

        ConfigDefaults.writeAll(port, loaderWith(tempDir));

        assertEquals(java.util.Arrays.asList("alpha.yml"), port.written);
    }

    @Test
    void 已存在的文件不被覆盖且不计入写入结果(@TempDir Path tempDir) throws IOException {
        addManifest(tempDir, "alpha.yml\n");
        RecordingConfigPort port = new RecordingConfigPort();
        port.existing.add("alpha.yml");

        List<String> written = ConfigDefaults.writeAll(port, loaderWith(tempDir));

        assertTrue(written.isEmpty(), "已存在的文件不应出现在新建列表里");
        assertEquals(java.util.Arrays.asList("alpha.yml"), port.attempted, "但仍然会被尝试一次");
    }

    @Test
    void 单份写失败不阻断其它(@TempDir Path tempDir) throws IOException {
        addManifest(tempDir, "broken.yml\nok.yml\n");
        RecordingConfigPort port = new RecordingConfigPort();
        port.failing.add("broken.yml");

        List<String> written = ConfigDefaults.writeAll(port, loaderWith(tempDir));

        assertEquals(java.util.Arrays.asList("ok.yml"), written, "其余文件照常写入");
    }

    @Test
    void 写入结果为只读视图(@TempDir Path tempDir) throws IOException {
        addManifest(tempDir, "alpha.yml\n");

        List<String> written = ConfigDefaults.writeAll(new RecordingConfigPort(), loaderWith(tempDir));

        assertFalse(written.isEmpty());
        try {
            written.add("injected.yml");
            throw new AssertionError("应当是不可变列表");
        } catch (UnsupportedOperationException expected) {
            // 预期
        }
    }

    /** 在临时目录下建一个只含清单的类路径根。 */
    private static void addManifest(Path root, String body) throws IOException {
        Path manifest = root.resolve(ConfigDefaults.MANIFEST_RESOURCE);
        Files.createDirectories(manifest.getParent());
        Files.write(manifest, body.getBytes(StandardCharsets.UTF_8));
    }

    /** 只暴露临时目录这一个类路径根的加载器（避开真实 jar 里的清单干扰）。 */
    private static ClassLoader loaderWith(Path root) throws IOException {
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
    }

    /** 记录调用、可按名字注入失败的配置端口桩。 */
    private static final class RecordingConfigPort implements ConfigPort {
        private final Map<String, Object> values = new HashMap<>();
        final List<String> written = new java.util.ArrayList<>();
        final List<String> attempted = new java.util.ArrayList<>();
        final java.util.Set<String> existing = new java.util.LinkedHashSet<>();
        final java.util.Set<String> failing = new java.util.LinkedHashSet<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> read(String fileName, Class<T> type) {
            return Optional.ofNullable((T) values.get(fileName));
        }

        @Override
        public boolean writeDefault(String fileName) {
            attempted.add(fileName);
            if (failing.contains(fileName)) {
                throw new IllegalStateException("模拟写入失败：" + fileName);
            }
            if (existing.contains(fileName)) {
                return false;
            }
            written.add(fileName);
            return true;
        }
    }
}
