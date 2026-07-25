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
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapters;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersions;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * Bukkit 家族平台入口：加载构建期唯一 L4 适配器，校验运行期 MC 版本后装配网络与能力。
 */
public final class BukkitPlatformBootstrap implements PlatformBootstrap {

    private final Function<Plugin, BukkitVersionAdapter> adapterLoader;

    public BukkitPlatformBootstrap() {
        this(
                plugin -> {
                    BukkitVersionAdapter adapter =
                            BukkitVersionAdapters.load(
                                    BukkitPlatformBootstrap.class.getClassLoader());
                    BukkitVersions.requireExactMatch(
                            adapter.minecraftVersion(), rawServerVersion(plugin));
                    return adapter;
                });
    }

    /** 测试注入：自定义适配器加载（可跳过真实 ServiceLoader）。 */
    BukkitPlatformBootstrap(Function<Plugin, BukkitVersionAdapter> adapterLoader) {
        this.adapterLoader = Objects.requireNonNull(adapterLoader, "adapterLoader 不能为空");
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
        BukkitVersionAdapter adapter = adapterLoader.apply(plugin);
        BukkitServerNetwork network =
                BukkitNetworkBindings.serverNetwork(adapter, plugin, connections);
        runtime.ports().register(TransportPort.class, new BukkitServerTransport(network));
        BukkitCapabilityBootstrap.register(plugin, runtime, featureGate(), connections, adapter);
    }

    private static String rawServerVersion(Plugin plugin) {
        try {
            Object version =
                    plugin.getServer()
                            .getClass()
                            .getMethod("getMinecraftVersion")
                            .invoke(plugin.getServer());
            if (version != null) {
                return version.toString();
            }
        } catch (ReflectiveOperationException ignored) {
            // 1.12 回退
        }
        return plugin.getServer().getBukkitVersion();
    }
}
