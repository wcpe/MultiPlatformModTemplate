package top.wcpe.mc.mpmt.platform.forge.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link Forge112DataDirectoryPort}：基目录 = 平台配置目录下的 {@code mpmt}。 */
class Forge112DataDirectoryPortTest {

    @Test
    void 基目录为配置目录下的mpmt(@TempDir Path temp) {
        Forge112DataDirectoryPort port = new Forge112DataDirectoryPort(temp.toFile());

        assertEquals(temp.resolve("mpmt"), port.baseDirectory());
    }

    @Test
    void 配置目录为空时报错() {
        assertThrows(NullPointerException.class, () -> new Forge112DataDirectoryPort((File) null));
    }
}
