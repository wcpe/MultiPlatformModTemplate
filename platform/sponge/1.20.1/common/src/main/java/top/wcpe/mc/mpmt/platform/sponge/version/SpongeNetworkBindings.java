package top.wcpe.mc.mpmt.platform.sponge.version;

import java.util.Objects;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.plugin.PluginContainer;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionRegistry;
import top.wcpe.mc.mpmt.platform.sponge.version.v1_20.V1_20SpongeServerNetwork;

/** Sponge 网络版本绑定的唯一装配点。 */
public final class SpongeNetworkBindings {

    private static final String CHANNEL_NAMESPACE = "mpmt";
    private static final String CHANNEL_PATH = "main";

    private SpongeNetworkBindings() {
        // 工具类不实例化
    }

    /** 构造所选锚点的服务端网络适配器。 */
    public static SpongeServerNetwork serverNetwork(
            SupportedVersion version,
            RegisterChannelEvent event,
            PluginContainer plugin,
            SpongeConnectionRegistry connections) {
        Objects.requireNonNull(version, "version 不能为空");
        switch (version) {
            case V1_20:
                return new V1_20SpongeServerNetwork(
                        event,
                        plugin,
                        connections,
                        ResourceKey.of(CHANNEL_NAMESPACE, CHANNEL_PATH));
            default:
                throw new IllegalStateException("缺少 Sponge 服务端网络适配：" + version);
        }
    }
}
