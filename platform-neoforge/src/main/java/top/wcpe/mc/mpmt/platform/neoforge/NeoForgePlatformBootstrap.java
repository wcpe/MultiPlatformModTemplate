package top.wcpe.mc.mpmt.platform.neoforge;

import net.minecraft.server.MinecraftServer;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.neoforge.capability.NeoForgeCapabilityBootstrap;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeServerTransport;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * NeoForge 平台入口（SPI 实现），经 {@code META-INF/services} 注册供 ServiceLoader 发现。
 *
 * <p>从启动上下文取得服务端与构造期已注册的产品传输，在运行时启用前统一注册全部服务端端口。
 */
public final class NeoForgePlatformBootstrap implements PlatformBootstrap {

    public NeoForgePlatformBootstrap() {
        // ServiceLoader 需要公开无参构造
    }

    @Override
    public String platformId() {
        return "neoforge";
    }

    @Override
    public FeatureGate featureGate() {
        return new NeoForgeFeatureGate();
    }

    @Override
    public void assemble(PlatformAssemblyContext context, MpmtRuntime runtime) {
        MinecraftServer server = context.get(MinecraftServer.class);
        NeoForgeServerTransport transport = context.get(NeoForgeServerTransport.class);
        runtime.ports().register(TransportPort.class, transport);
        NeoForgeCapabilityBootstrap.register(server, runtime);
    }
}
