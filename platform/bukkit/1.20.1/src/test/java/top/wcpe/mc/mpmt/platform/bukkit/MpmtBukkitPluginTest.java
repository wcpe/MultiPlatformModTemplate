package top.wcpe.mc.mpmt.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.command.PluginCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * Bukkit 插件装配链路集成测试（MockBukkit，无需真实服，FR-23）。
 *
 * <p>只验证"插件启用 → 经 SPI 发现并装配唯一活跃平台 → FeatureGate 分流"。真实 Paper 服装配为实机维度（用户确认）。
 * 注：{@link PlatformProvider} 为进程级静态 Holder，本模块测试只 boot 一次（跨用例无法重置）。
 */
class MpmtBukkitPluginTest {

    @AfterEach
    void 拆除Mock() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    @DisplayName("插件启用后：平台装配为 bukkit，FeatureGate 在非 Folia/非集成服下正确分流")
    void 插件启用后平台装配为bukkit() {
        MockBukkit.mock(new AnchorServerMock());
        MpmtBukkitPlugin plugin = MockBukkit.load(MpmtBukkitPlugin.class);

        assertTrue(plugin.isEnabled(), "插件应已启用");
        PluginCommand command = plugin.getCommand("mpmt");
        assertNotNull(command, "应注册原生 /mpmt 命令");
        assertEquals(
                "mpmt.machinecode.manage",
                command.getPermission(),
                "机器码管理命令应声明权限");
        assertTrue(PlatformProvider.isBooted(), "平台应已装配");
        assertEquals("bukkit", PlatformProvider.get().platformId());
        // 验收产品入口（R5/R6 反射桥）：活跃平台与融合服门控
        assertEquals("bukkit", plugin.activePlatformId());
        assertFalse(plugin.isHybridForgeBukkit());
        assertNotNull(plugin.schedulerPortClassName());
        assertTrue(plugin.schedulerPortClassName().contains("SchedulerPort"));
        // 服务端传输端口（FR-20）已接线：插件启用时注册了产品跨端通道 mpmt:main
        assertTrue(
                plugin.getServer().getMessenger().isOutgoingChannelRegistered(plugin, "mpmt:main"),
                "插件应已注册产品跨端通道 mpmt:main 的出站方向");
        assertTrue(
                plugin.getServer().getMessenger().isIncomingChannelRegistered(plugin, "mpmt:main"),
                "插件应已注册产品跨端通道 mpmt:main 的入站方向");
        // MockBukkit 非 Folia、非融合服、非集成服
        assertFalse(PlatformProvider.get().featureGate().supports(Capability.REGION_SCHEDULER));
        assertFalse(PlatformProvider.get().featureGate().supports(Capability.HYBRID_FORGE_BUKKIT));
        assertFalse(PlatformProvider.get().featureGate().supports(Capability.INTEGRATED_SERVER));
    }

    /** 仅覆盖 MockBukkit 固定返回 1.20.4，令装配测试模拟当前 1.20.1 锚点。 */
    private static final class AnchorServerMock extends ServerMock {

        @Override
        public String getMinecraftVersion() {
            return "1.20.1";
        }
    }
}
