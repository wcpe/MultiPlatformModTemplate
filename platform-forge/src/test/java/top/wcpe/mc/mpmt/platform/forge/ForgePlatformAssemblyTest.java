package top.wcpe.mc.mpmt.platform.forge;

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
 * Forge 平台装配链路（纯 JVM）：经真实 ServiceLoader 发现 Forge 平台入口、FeatureGate 分流。
 *
 * <p>用 {@link PlatformAssembler#discover} 验证发现路径而不触碰静态 Holder。
 * 完整运行（真实客户端 / 服务端 / GameTest 模拟服）为实机维度，随网络 / smoke 特性落地。
 */
class ForgePlatformAssemblyTest {

    @Test
    @DisplayName("经 ServiceLoader 发现唯一 Forge 平台入口")
    void 发现Forge平台入口() {
        PlatformBootstrap bootstrap = PlatformAssembler.discover(getClass().getClassLoader());
        assertEquals("forge", bootstrap.platformId());
        assertNotNull(bootstrap.featureGate());
        assertDoesNotThrow(() -> bootstrap.assemble(new RuntimePorts()));
    }

    @Test
    @DisplayName("FeatureGate：Forge 非 Folia")
    void FeatureGate分流() {
        ForgeFeatureGate gate = new ForgeFeatureGate();
        assertFalse(gate.supports(Capability.REGION_SCHEDULER));
    }
}
