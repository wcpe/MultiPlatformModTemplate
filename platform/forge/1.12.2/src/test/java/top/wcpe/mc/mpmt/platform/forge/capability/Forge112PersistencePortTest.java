package top.wcpe.mc.mpmt.platform.forge.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link Forge112PersistencePort}：每命名空间一份 properties，缺键回空、不静默吞错。 */
class Forge112PersistencePortTest {

    @Test
    void 写入后能读回(@TempDir Path temp) {
        Forge112PersistencePort port = port(temp);

        port.write("counter", "join-count", "7");

        assertEquals(Optional.of("7"), port.read("counter", "join-count"));
        assertTrue(Files.isRegularFile(temp.resolve("mpmt").resolve("data").resolve("counter.properties")));
    }

    @Test
    void 缺失的键与命名空间都回空(@TempDir Path temp) {
        Forge112PersistencePort port = port(temp);

        assertFalse(port.read("never-written", "key").isPresent());
        port.write("counter", "a", "1");
        assertFalse(port.read("counter", "b").isPresent());
    }

    @Test
    void 写入会覆盖旧值并保留同命名空间其它键(@TempDir Path temp) {
        Forge112PersistencePort port = port(temp);

        port.write("counter", "a", "1");
        port.write("counter", "b", "2");
        port.write("counter", "a", "3");

        assertEquals(Optional.of("3"), port.read("counter", "a"));
        assertEquals(Optional.of("2"), port.read("counter", "b"));
    }

    @Test
    void 命名空间之间互不干扰(@TempDir Path temp) {
        Forge112PersistencePort port = port(temp);

        port.write("one", "k", "1");
        port.write("two", "k", "2");

        assertEquals(Optional.of("1"), port.read("one", "k"));
        assertEquals(Optional.of("2"), port.read("two", "k"));
    }

    @Test
    void 文件损坏时报错而不是静默回空(@TempDir Path temp) throws Exception {
        Forge112PersistencePort port = port(temp);
        Path file = temp.resolve("mpmt").resolve("data").resolve("broken.properties");
        Files.createDirectories(file.getParent());
        // properties 读到非法的 unicode 转义序列会抛 IllegalArgumentException（而非 IOException）
        Files.write(file, "k=\\uZZZZ".getBytes(StandardCharsets.UTF_8));

        assertThrows(RuntimeException.class, () -> port.read("broken", "k"));
    }

    @Test
    void 基目录端口为空时报错() {
        assertThrows(NullPointerException.class, () -> new Forge112PersistencePort(null));
    }

    private static Forge112PersistencePort port(Path temp) {
        return new Forge112PersistencePort(new Forge112DataDirectoryPort(temp.toFile()));
    }
}
