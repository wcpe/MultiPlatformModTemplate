package top.wcpe.mc.mpmt.platform.forge;

import java.io.File;
import net.minecraft.server.MinecraftServer;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.forge.capability.Forge112CapabilityBootstrap;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * Forge 1.12.2 平台入口（SPI 实现），经 {@code META-INF/services} 注册供 ServiceLoader 发现。
 *
 * <p>与其它车道同构：入口只负责把平台原生对象放进启动上下文，真正的端口注册在
 * {@link Forge112CapabilityBootstrap}；装配统一由 {@code PlatformProvider.boot} 驱动，
 * 因而参与进程级「单一活跃平台」把关（ADR-0008 / FR-25）。
 */
public final class Forge112PlatformBootstrap implements PlatformBootstrap {

    /** ServiceLoader 需要公开无参构造。 */
    public Forge112PlatformBootstrap() {
        // 无需初始化
    }

    @Override
    public String platformId() {
        return "forge";
    }

    @Override
    public FeatureGate featureGate() {
        return new Forge112FeatureGate();
    }

    @Override
    public void assemble(PlatformAssemblyContext context, MpmtRuntime runtime) {
        MinecraftServer server = context.get(MinecraftServer.class);
        File configDirectory = context.get(File.class);
        Forge112CapabilityBootstrap.register(server, configDirectory, runtime);
    }
}
