package top.wcpe.mc.mpmt.platform.bukkit;

import java.util.Objects;
import java.util.function.Function;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.bukkit.capability.BukkitCapabilityBootstrap;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionRegistry;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitServerTransport;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitNetworkBindings;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitServerNetwork;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersions;
import top.wcpe.mc.mpmt.platform.bukkit.version.SupportedVersion;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/** Bukkit 家族平台入口：探测实际 MC 版本后装配对应 L4 网络适配器与平台能力。 */
public final class BukkitPlatformBootstrap implements PlatformBootstrap {

    private final Function<Plugin, SupportedVersion> versionDetector;

    public BukkitPlatformBootstrap() {
        this(BukkitVersions::detect);
    }

    BukkitPlatformBootstrap(Function<Plugin, SupportedVersion> versionDetector) {
        this.versionDetector = Objects.requireNonNull(versionDetector, "versionDetector 不能为空");
    }

    @Override
    public String platformId() {
        return "bukkit";
    }

    @Override
    public FeatureGate featureGate() {
        return new BukkitFeatureGate();
    }

    @Override
    public void assemble(PlatformAssemblyContext context, MpmtRuntime runtime) {
        Plugin plugin = context.get(Plugin.class);
        BukkitConnectionRegistry connections = new BukkitConnectionRegistry();
        SupportedVersion version = versionDetector.apply(plugin);
        BukkitServerNetwork network =
                BukkitNetworkBindings.serverNetwork(version, plugin, connections);
        runtime.ports().register(TransportPort.class, new BukkitServerTransport(network));
        BukkitCapabilityBootstrap.register(plugin, runtime, featureGate(), connections);
    }
}
