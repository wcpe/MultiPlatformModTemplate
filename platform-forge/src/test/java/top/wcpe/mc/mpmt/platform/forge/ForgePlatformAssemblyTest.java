package top.wcpe.mc.mpmt.platform.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.forge.version.ForgeServerNetwork;
import top.wcpe.mc.mpmt.platform.forge.version.v1_20.V1_20ServerNetwork;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssembler;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyException;
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
        PlatformAssemblyException error =
                assertThrows(
                        PlatformAssemblyException.class,
                        () -> bootstrap.assemble(new PlatformAssemblyContext(), new MpmtRuntime()));
        assertTrue(error.getMessage().contains("MinecraftServer"));
    }

    @Test
    @DisplayName("Forge 入口使用运行期探测结果选择 L4 adapter")
    void 入口使用探测后的Adapter() {
        AtomicBoolean probed = new AtomicBoolean();
        ForgeServerNetwork network =
                MpmtForgeMod.detectServerNetwork(
                        () -> {
                            probed.set(true);
                            return "1.20.1";
                        });

        assertTrue(probed.get());
        assertInstanceOf(V1_20ServerNetwork.class, network);
    }

    @Test
    @DisplayName("FeatureGate：Forge 非 Folia")
    void FeatureGate分流() {
        ForgeFeatureGate gate = new ForgeFeatureGate();
        assertFalse(gate.supports(Capability.REGION_SCHEDULER));
    }
}
