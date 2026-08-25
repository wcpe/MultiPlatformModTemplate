package top.wcpe.mc.mpmt.platform.forge.modern.capability;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgePersistencePortTest {

    @TempDir private Path tempDirectory;

    @Test
    void 读取目录时抛出持久化异常() throws Exception {
        Files.createDirectories(tempDirectory.resolve("data").resolve("blocked.properties"));

        ForgePersistencePort port = new ForgePersistencePort(() -> tempDirectory);

        assertThrows(UncheckedIOException.class, () -> port.read("blocked", "key"));
    }

    @Test
    void 数据目录被文件占用时写入抛出持久化异常() throws Exception {
        Files.write(tempDirectory.resolve("data"), new byte[] {1});

        ForgePersistencePort port = new ForgePersistencePort(() -> tempDirectory);

        assertThrows(UncheckedIOException.class, () -> port.write("blocked", "key", "value"));
    }
}
