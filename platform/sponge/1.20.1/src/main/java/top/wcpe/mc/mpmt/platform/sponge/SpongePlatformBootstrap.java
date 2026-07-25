package top.wcpe.mc.mpmt.platform.sponge;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.plugin.PluginContainer;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.sponge.capability.SpongeCapabilityBootstrap;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionRegistry;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeServerTransport;
import top.wcpe.mc.mpmt.platform.sponge.version.SpongeNetworkBindings;
import top.wcpe.mc.mpmt.platform.sponge.version.SpongeServerNetwork;
import top.wcpe.mc.mpmt.platform.sponge.version.SpongeVersions;
import top.wcpe.mc.mpmt.platform.sponge.version.SupportedVersion;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/** Sponge 平台入口：探测实际 MC 版本后装配对应 L4 网络适配器与平台能力。 */
public final class SpongePlatformBootstrap implements PlatformBootstrap {

    private final Supplier<SupportedVersion> versionDetector;
    private final NetworkFactory networkFactory;

    public SpongePlatformBootstrap() {
        this(SpongeVersions::detect, SpongeNetworkBindings::serverNetwork);
    }

    SpongePlatformBootstrap(
            Supplier<SupportedVersion> versionDetector, NetworkFactory networkFactory) {
        this.versionDetector = Objects.requireNonNull(versionDetector, "versionDetector 不能为空");
        this.networkFactory = Objects.requireNonNull(networkFactory, "networkFactory 不能为空");
    }

    @Override
    public String platformId() {
        return "sponge";
    }

    @Override
    public FeatureGate featureGate() {
        return new SpongeFeatureGate();
    }

    @Override
    public void assemble(PlatformAssemblyContext context, MpmtRuntime runtime) {
        PluginContainer plugin = context.get(PluginContainer.class);
        Path configDir = context.get(Path.class);
        SpongeConnectionRegistry connections = registerTransport(context, runtime);
        SpongeCapabilityBootstrap.register(plugin, configDir, runtime, connections);
    }

    SpongeConnectionRegistry registerTransport(
            PlatformAssemblyContext context, MpmtRuntime runtime) {
        PluginContainer plugin = context.get(PluginContainer.class);
        RegisterChannelEvent event = context.get(RegisterChannelEvent.class);
        SpongeConnectionRegistry connections = new SpongeConnectionRegistry();
        SupportedVersion version = versionDetector.get();
        SpongeServerNetwork network = networkFactory.create(version, event, plugin, connections);
        runtime.ports().register(TransportPort.class, new SpongeServerTransport(network));
        return connections;
    }

    @FunctionalInterface
    interface NetworkFactory {
        SpongeServerNetwork create(
                SupportedVersion version,
                RegisterChannelEvent event,
                PluginContainer plugin,
                SpongeConnectionRegistry connections);
    }
}
