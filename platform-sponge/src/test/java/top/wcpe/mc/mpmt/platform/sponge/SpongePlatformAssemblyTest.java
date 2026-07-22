package top.wcpe.mc.mpmt.platform.sponge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssembler;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyException;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * Sponge 平台装配链路（纯 JVM）：经真实 ServiceLoader 发现 Sponge 平台入口、FeatureGate 分流。
 *
 * <p>用 {@link PlatformAssembler#discover} 验证发现路径而不触碰静态 Holder。完整运行（真实 SpongeVanilla 服 +
 * 我方 Fabric 客户端伴侣 realserver）为实机维度，随网络 / 能力 / acceptance 落地。
 */
class SpongePlatformAssemblyTest {

    @Test
    @DisplayName("经 ServiceLoader 发现唯一 Sponge 平台入口")
    void 发现Sponge平台入口() {
        PlatformBootstrap bootstrap = PlatformAssembler.discover(getClass().getClassLoader());
        assertEquals("sponge", bootstrap.platformId());
        assertNotNull(bootstrap.featureGate());
        PlatformAssemblyException error =
                assertThrows(
                        PlatformAssemblyException.class,
                        () -> bootstrap.assemble(new PlatformAssemblyContext(), new MpmtRuntime()));
        assertTrue(error.getMessage().contains("PluginContainer"));
    }

    @Test
    @DisplayName("FeatureGate：Sponge 非 Folia")
    void FeatureGate分流() {
        SpongeFeatureGate gate = new SpongeFeatureGate();
        assertFalse(gate.supports(Capability.REGION_SCHEDULER));
    }

    @Test
    @DisplayName("使用 RC1365 参数化命令注册事件")
    void 使用RC1365参数化命令注册事件() throws NoSuchMethodException {
        Method listener =
                MpmtSpongePlugin.class.getMethod("onRegisterCommands", RegisterCommandEvent.class);
        assertEquals(void.class, listener.getReturnType());
        assertNotNull(Command.Parameterized.class);
    }
}
