package top.wcpe.mc.mpmt.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.neoforge.version.NeoForgeServerNetwork;
import top.wcpe.mc.mpmt.platform.neoforge.version.v1_20_2.V1_20_2ServerNetwork;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssembler;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * NeoForge 平台装配链路（纯 JVM）：经真实 ServiceLoader 发现 NeoForge 平台入口、FeatureGate 分流。
 *
 * <p>用 {@link PlatformAssembler#discover} 验证发现路径而不触碰静态 Holder。完整运行（真实客户端 / 服务端 /
 * GameTest 模拟服）为实机维度，随网络 / 示例特性落地。
 */
class NeoForgePlatformAssemblyTest {

    @Test
    @DisplayName("经 ServiceLoader 发现唯一 NeoForge 平台入口")
    void 发现NeoForge平台入口() {
        PlatformBootstrap bootstrap = PlatformAssembler.discover(getClass().getClassLoader());
        assertEquals("neoforge", bootstrap.platformId());
        assertNotNull(bootstrap.featureGate());
    }

    @Test
    @DisplayName("NeoForge 入口使用运行期探测结果选择 L4 adapter")
    void 入口使用探测后的Adapter() {
        AtomicBoolean probed = new AtomicBoolean();
        NeoForgeServerNetwork network =
                MpmtNeoForgeMod.detectServerNetwork(
                        () -> {
                            probed.set(true);
                            return "1.20.2";
                        });

        assertTrue(probed.get());
        assertInstanceOf(V1_20_2ServerNetwork.class, network);
    }

    @Test
    @DisplayName("FeatureGate：NeoForge 非 Folia")
    void FeatureGate分流() {
        NeoForgeFeatureGate gate = new NeoForgeFeatureGate();
        assertFalse(gate.supports(Capability.REGION_SCHEDULER));
    }
}
