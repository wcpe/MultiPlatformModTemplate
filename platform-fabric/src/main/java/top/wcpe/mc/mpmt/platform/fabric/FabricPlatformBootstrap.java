package top.wcpe.mc.mpmt.platform.fabric;

import net.minecraft.server.MinecraftServer;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.fabric.capability.FabricCapabilityBootstrap;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricServerTransport;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricNetworkBindings;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricServerNetwork;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricVersions;
import top.wcpe.mc.mpmt.platform.fabric.version.SupportedVersion;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * Fabric 平台入口（SPI 实现），经 {@code META-INF/services} 注册供 ServiceLoader 发现。
 *
 * <p>装配期从启动上下文取得服务端，探测 MC 版本并注册传输及全部服务端能力端口；
 * 玩家事件统一桥接到运行时自有 EventBus。
 */
public final class FabricPlatformBootstrap implements PlatformBootstrap {

    public FabricPlatformBootstrap() {
        // ServiceLoader 需要公开无参构造
    }

    @Override
    public String platformId() {
        return "fabric";
    }

    @Override
    public FeatureGate featureGate() {
        return new FabricFeatureGate();
    }

    @Override
    public void assemble(PlatformAssemblyContext context, MpmtRuntime runtime) {
        MinecraftServer server = context.get(MinecraftServer.class);
        // 探测当前 MC 版本，选中匹配的 vX_Y 网络绑定（缺失即失败快，ADR-0003）
        SupportedVersion version = FabricVersions.detect();
        FabricServerNetwork network = FabricNetworkBindings.serverNetwork(version);
        runtime.ports().register(TransportPort.class, new FabricServerTransport(network));
        FabricCapabilityBootstrap.register(server, runtime);
    }
}
