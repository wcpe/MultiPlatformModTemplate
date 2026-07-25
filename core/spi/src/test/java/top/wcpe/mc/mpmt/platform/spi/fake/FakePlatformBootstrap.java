package top.wcpe.mc.mpmt.platform.spi.fake;

import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * 测试用假平台入口（test double）：经 {@code META-INF/services} 注册供 ServiceLoader 发现。
 *
 * <p>必须 public + 公开无参构造，否则 ServiceLoader 无法实例化。
 */
public final class FakePlatformBootstrap implements PlatformBootstrap {

    public FakePlatformBootstrap() {
        // ServiceLoader 需要公开无参构造
    }

    @Override
    public String platformId() {
        return "fake";
    }

    @Override
    public FeatureGate featureGate() {
        // 仅支持集成服能力，用于断言能力探测分流
        return capability -> capability == Capability.INTEGRATED_SERVER;
    }

    @Override
    public void assemble(PlatformAssemblyContext context, MpmtRuntime runtime) {
        String portId = context.find(String.class).orElse("fake-port");
        runtime.ports().register(FakePort.class, () -> portId);
    }
}
