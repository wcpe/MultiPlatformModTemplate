package top.wcpe.mc.mpmt.platform.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssembler;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyException;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * Fabric 平台装配链路（纯 JVM）：经真实 ServiceLoader 发现 Fabric 平台入口、FeatureGate 分流。
 *
 * <p>用 {@link PlatformAssembler#discover} 验证发现路径而不触碰静态 Holder（不污染进程状态）。
 * {@code assemble()} 需启动上下文中的 MinecraftServer，完整运行属 GameTest 模拟服维度；
 * 纯 JVM 下验证缺少必需上下文即失败快。
 */
class FabricPlatformAssemblyTest {

    @Test
    @DisplayName("经 ServiceLoader 发现唯一 Fabric 平台入口")
    void 发现Fabric平台入口() {
        PlatformBootstrap bootstrap = PlatformAssembler.discover(getClass().getClassLoader());
        assertEquals("fabric", bootstrap.platformId());
        assertNotNull(bootstrap.featureGate());
    }

    @Test
    @DisplayName("assemble 缺服务端启动上下文即失败快（不静默装配残缺端口）")
    void 装配缺启动上下文失败快() {
        PlatformBootstrap bootstrap = PlatformAssembler.discover(getClass().getClassLoader());
        PlatformAssemblyException ex =
                assertThrows(
                        PlatformAssemblyException.class,
                        () -> bootstrap.assemble(new PlatformAssemblyContext(), new MpmtRuntime()));
        assertTrue(ex.getMessage().contains("MinecraftServer"));
    }

    @Test
    @DisplayName("FeatureGate：Fabric 非 Folia、非融合服")
    void FeatureGate分流() {
        FabricFeatureGate gate = new FabricFeatureGate();
        assertFalse(gate.supports(Capability.REGION_SCHEDULER));
        assertFalse(gate.supports(Capability.HYBRID_FORGE_BUKKIT));
    }
}
