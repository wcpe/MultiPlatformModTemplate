package top.wcpe.mc.mpmt.platform.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.runtime.RuntimePorts;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssembler;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * Fabric 平台装配链路（纯 JVM）：经真实 ServiceLoader 发现 Fabric 平台入口、FeatureGate 分流。
 *
 * <p>用 {@link PlatformAssembler#discover} 验证发现路径而不触碰静态 Holder（不污染进程状态）。
 * {@code assemble()} 现需探测 MC 版本并装配 L4 网络绑定（FR-10/FR-20），依赖 Fabric 运行时，
 * 故其<b>完整运行</b>属 GameTest 模拟服维度（随网络 GameTest 套件落地）；纯 JVM 下只验证它<b>缺运行时即失败快</b>。
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
    @DisplayName("assemble 缺 Fabric/MC 运行时即失败快（不静默装配残缺端口）")
    void 装配缺运行时失败快() {
        PlatformBootstrap bootstrap = PlatformAssembler.discover(getClass().getClassLoader());
        // 纯 JVM 无 Fabric 运行时，探测 MC 版本必失败；钉到预期根因（探测失败的 IllegalStateException），
        // 而非笼统 RuntimeException——避免把环境性类加载错误误判为"设计的失败快"
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> bootstrap.assemble(new RuntimePorts()));
        assertTrue(ex.getMessage().contains("Minecraft"));
    }

    @Test
    @DisplayName("FeatureGate：Fabric 非 Folia、非融合服")
    void FeatureGate分流() {
        FabricFeatureGate gate = new FabricFeatureGate();
        assertFalse(gate.supports(Capability.REGION_SCHEDULER));
        assertFalse(gate.supports(Capability.HYBRID_FORGE_BUKKIT));
    }
}
