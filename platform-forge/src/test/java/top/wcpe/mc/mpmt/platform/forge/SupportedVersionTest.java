package top.wcpe.mc.mpmt.platform.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.forge.version.SupportedVersion;

/** Forge L4 当前锚点选择与未知版本失败快测试。 */
class SupportedVersionTest {

    @Test
    @DisplayName("Forge 当前锚点 1.20.1 命中 v1_20")
    void 当前锚点命中() {
        assertSame(SupportedVersion.V1_20, SupportedVersion.match("1.20.1"));
        assertEquals("1.20.1", SupportedVersion.V1_20.mcVersion());
    }

    @Test
    @DisplayName("Forge 未知版本明确失败而非静默退化")
    void 未知版本失败快() {
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match("1.20.2"));
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match("1.21.1"));
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match(""));
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match(null));
    }
}
