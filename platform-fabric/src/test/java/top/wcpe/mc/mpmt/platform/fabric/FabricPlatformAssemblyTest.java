package top.wcpe.mc.mpmt.platform.fabric;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * 完整运行（真实客户端 / GameTest 模拟服）为实机维度，随网络 / smoke 特性的 GameTest 套件落地。
 */
class FabricPlatformAssemblyTest {

    @Test
    @DisplayName("经 ServiceLoader 发现唯一 Fabric 平台入口")
    void 发现Fabric平台入口() {
        PlatformBootstrap bootstrap = PlatformAssembler.discover(getClass().getClassLoader());
        assertEquals("fabric", bootstrap.platformId());
        assertNotNull(bootstrap.featureGate());
        assertDoesNotThrow(() -> bootstrap.assemble(new RuntimePorts()));
    }

    @Test
    @DisplayName("FeatureGate：Fabric 非 Folia、非融合服")
    void FeatureGate分流() {
        FabricFeatureGate gate = new FabricFeatureGate();
        assertFalse(gate.supports(Capability.REGION_SCHEDULER));
        assertFalse(gate.supports(Capability.HYBRID_FORGE_BUKKIT));
    }
}
