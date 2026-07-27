package top.wcpe.mc.mpmt.platform.bukkit.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bukkit L4 版本选择、通道、运行时校验与调度装配契约。 */
class BukkitVersionApiTest {

    @Test
    @DisplayName("四个锚点精确命中，未知版本明确拒绝")
    void 版本选择与未知拒绝() {
        assertSame(SupportedVersion.V1_12, SupportedVersion.match("1.12.2"));
        assertSame(SupportedVersion.V1_20, SupportedVersion.match("1.20.1"));
        assertSame(SupportedVersion.V1_21, SupportedVersion.match("1.21.1"));
        assertSame(SupportedVersion.V26_2, SupportedVersion.match("26.2"));
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match("1.20.2"));
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match(""));
        assertThrows(IllegalStateException.class, () -> SupportedVersion.match(null));
    }

    @Test
    @DisplayName("Bukkit 版本后缀可解析且实际版本不符时失败快")
    void 实际版本必须匹配构建目标() {
        BukkitVersions.requireExactMatch("1.20.1", "1.20.1-R0.1-SNAPSHOT");
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> BukkitVersions.requireExactMatch("1.21.1", "1.20.1-R0.1-SNAPSHOT"));

        assertTrue(error.getMessage().contains("expected=1.21.1"));
        assertTrue(error.getMessage().contains("actual=1.20.1-R0.1-SNAPSHOT"));
    }

    @Test
    @DisplayName("选中适配器提供目标版本对应的产品通道")
    void 通道映射匹配目标版本() {
        BukkitVersionAdapter adapter = selectedAdapter();

        assertEquals(System.getProperty("mpmt.test.minecraftVersion"), adapter.minecraftVersion());
        assertEquals(System.getProperty("mpmt.test.productChannel"), adapter.channels().product());
    }

    private static BukkitVersionAdapter selectedAdapter() {
        return BukkitVersionAdapters.load(BukkitVersionApiTest.class.getClassLoader());
    }
}
