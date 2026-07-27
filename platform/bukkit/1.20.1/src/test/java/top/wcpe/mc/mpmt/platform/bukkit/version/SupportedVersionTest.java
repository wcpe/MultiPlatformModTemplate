package top.wcpe.mc.mpmt.platform.bukkit.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bukkit L4 版本锚点选择测试。 */
class SupportedVersionTest {

    @Test
    @DisplayName("三锚点精确命中")
    void 三锚点命中() {
        assertSame(SupportedVersion.V1_12, SupportedVersion.match("1.12.2"));
        assertSame(SupportedVersion.V1_20, SupportedVersion.match("1.20.1"));
        assertSame(SupportedVersion.V1_21, SupportedVersion.match("1.21.1"));
        assertSame(SupportedVersion.V26_2, SupportedVersion.match("26.2"));
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
