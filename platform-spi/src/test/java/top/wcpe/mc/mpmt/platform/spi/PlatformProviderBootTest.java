package top.wcpe.mc.mpmt.platform.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.spi.fake.FakePort;

/** 经 ServiceLoader 发现并装配唯一活跃平台（集成测试，用 META-INF/services 注册的假平台）。 */
class PlatformProviderBootTest {

    @BeforeEach
    @AfterEach
    void 重置Holder() {
        // 静态 Holder 跨用例会相互影响，逐例重置
        PlatformProvider.resetForTesting();
    }

    @Test
    @DisplayName("boot：发现唯一平台、注入端口、固化只读状态")
    void boot发现并装配() {
        MpmtRuntime runtime = new MpmtRuntime();

        PlatformProvider provider = PlatformProvider.boot(getClass().getClassLoader(), runtime);

        assertSame(provider, PlatformProvider.get());
        assertTrue(PlatformProvider.isBooted());
        assertEquals("fake", provider.platformId());
        // 能力探测分流：支持集成服、不支持区域调度
        assertTrue(provider.featureGate().supports(Capability.INTEGRATED_SERVER));
        assertFalse(provider.featureGate().supports(Capability.REGION_SCHEDULER));
        // 端口已被平台装配进运行时
        FakePort port = runtime.ports().get(FakePort.class);
        assertNotNull(port);
        assertEquals("fake-port", port.id());
    }

    @Test
    @DisplayName("重复 boot：失败快")
    void 重复boot失败快() {
        PlatformProvider.boot(getClass().getClassLoader(), new MpmtRuntime());
        assertThrows(PlatformAssemblyException.class,
                () -> PlatformProvider.boot(getClass().getClassLoader(), new MpmtRuntime()));
    }

    @Test
    @DisplayName("未装配即取用：失败快")
    void 未装配get失败快() {
        assertFalse(PlatformProvider.isBooted());
        assertThrows(IllegalStateException.class, PlatformProvider::get);
    }

    @Test
    @DisplayName("进程内已有我方活跃平台绑定：再激活第二入口失败快（融合服多入口，ADR-0008/FR-25）")
    void 进程内多入口同时激活失败快() {
        // 模拟同进程另一我方入口（如融合服上 Forge mod、不同类加载器）已激活：进程级标记已置、本类 instance 仍为 null。
        // per-classloader 的 instance 检查跨类加载器拦不住，须由进程级系统属性把关。
        System.setProperty(PlatformProvider.ACTIVE_PLATFORM_PROPERTY, "forge");
        assertThrows(
                PlatformAssemblyException.class,
                () -> PlatformProvider.boot(getClass().getClassLoader(), new MpmtRuntime()));
        // 标记清理在 @AfterEach 的 resetForTesting 内完成
    }

    @Test
    @DisplayName("deactivate 后可重新激活：释放进程级绑定，支持同 JVM 重新启用（/reload）")
    void deactivate后可重新激活() {
        PlatformProvider.boot(getClass().getClassLoader(), new MpmtRuntime());
        PlatformProvider.deactivate();
        assertFalse(PlatformProvider.isBooted());
        // 进程级标记已清，可再次 boot 不被误拦
        PlatformProvider provider =
                PlatformProvider.boot(getClass().getClassLoader(), new MpmtRuntime());
        assertEquals("fake", provider.platformId());
    }
}
