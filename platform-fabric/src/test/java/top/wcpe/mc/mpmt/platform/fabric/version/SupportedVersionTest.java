package top.wcpe.mc.mpmt.platform.fabric.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L4 版本探测与锚点选择（FR-10）：纯逻辑、不依赖 MC。 */
class SupportedVersionTest {

    @Test
    @DisplayName("锚点 1.20.1 命中 V1_20")
    void 命中锚点() {
        assertSame(SupportedVersion.V1_20, SupportedVersion.match("1.20.1"));
        assertEquals("1.20.1", SupportedVersion.V1_20.mcVersion());
    }

    @Test
    @DisplayName("非锚点版本：明确失败而非静默退化")
    void 缺失版本失败快() {
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match("1.20.2"));
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match("1.21.1"));
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match(""));
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match(null));
    }
}
