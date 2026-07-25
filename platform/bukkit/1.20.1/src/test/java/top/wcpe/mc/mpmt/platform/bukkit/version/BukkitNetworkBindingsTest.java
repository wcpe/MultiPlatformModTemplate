package top.wcpe.mc.mpmt.platform.bukkit.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.bukkit.version.v1_20.V1_20BukkitVersionAdapter;

/** Bukkit 网络绑定与 L4 ServiceLoader 适配器测试（默认 1.20.1 车道）。 */
class BukkitNetworkBindingsTest {

    @Test
    @DisplayName("默认产物经 ServiceLoader 得到 V1_20 适配器")
    void v1_20适配器元数据() {
        BukkitVersionAdapter adapter = BukkitNetworkBindings.adapterFor(SupportedVersion.V1_20);
        assertSame(SupportedVersion.V1_20, adapter.version());
        assertEquals("mpmt:main", adapter.channels().product());
        assertEquals(V1_20BukkitVersionAdapter.class, adapter.getClass());
    }

    @Test
    @DisplayName("adapterFor 拒绝 null")
    void adapterFor拒绝null() {
        assertThrows(NullPointerException.class, () -> BukkitNetworkBindings.adapterFor(null));
    }

    @Test
    @DisplayName("BukkitChannels 拒绝空产品通道")
    void channels拒绝空() {
        assertThrows(NullPointerException.class, () -> new BukkitChannels(null));
        assertThrows(IllegalArgumentException.class, () -> new BukkitChannels(""));
        assertThrows(IllegalArgumentException.class, () -> new BukkitChannels("  "));
        assertNotNull(new BukkitChannels("mpmt:main").product());
    }
}
