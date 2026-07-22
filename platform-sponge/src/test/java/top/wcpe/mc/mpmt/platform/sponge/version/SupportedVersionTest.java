package top.wcpe.mc.mpmt.platform.sponge.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Sponge L4 版本锚点选择测试。 */
class SupportedVersionTest {

    @Test
    @DisplayName("RC1365 当前锚点 1.20.1 命中 V1_20")
    void 当前锚点命中() {
        assertSame(SupportedVersion.V1_20, SupportedVersion.match("1.20.1"));
        assertEquals("1.20.1", SupportedVersion.V1_20.mcVersion());
    }

    @Test
    @DisplayName("未知版本明确失败快")
    void 未知版本失败快() {
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> SupportedVersion.match("1.20.2"));

        assertTrue(error.getMessage().contains("1.20.2"));
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match(""));
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match(null));
    }
}
