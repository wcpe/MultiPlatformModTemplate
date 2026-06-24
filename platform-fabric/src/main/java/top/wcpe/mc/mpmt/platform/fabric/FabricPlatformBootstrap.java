package top.wcpe.mc.mpmt.platform.fabric;

import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.RuntimePorts;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricServerTransport;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricNetworkBindings;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricServerNetwork;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricVersions;
import top.wcpe.mc.mpmt.platform.fabric.version.SupportedVersion;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * Fabric 平台入口（SPI 实现），经 {@code META-INF/services} 注册供 ServiceLoader 发现。
 *
 * <p>装配期探测 MC 版本 → 选中 {@link SupportedVersion} 锚点 → 装配其 L4 网络绑定 → 注册服务端
 * {@link TransportPort}。其余端口（调度 / 消息 / 持久化）随后续特性增量注入（用到才建，scope-discipline）。
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
    public void assemble(RuntimePorts ports) {
        // 探测当前 MC 版本，选中匹配的 vX_Y 网络绑定（缺失即失败快，ADR-0003）
        SupportedVersion version = FabricVersions.detect();
        FabricServerNetwork network = FabricNetworkBindings.serverNetwork(version);
        ports.register(TransportPort.class, new FabricServerTransport(network));
    }
}
