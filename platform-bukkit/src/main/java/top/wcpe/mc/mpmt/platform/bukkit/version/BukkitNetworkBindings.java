package top.wcpe.mc.mpmt.platform.bukkit.version;

import java.util.Objects;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionRegistry;
import top.wcpe.mc.mpmt.platform.bukkit.version.v1_20.V1_20BukkitServerNetwork;

/** Bukkit 网络版本绑定的唯一装配点。 */
public final class BukkitNetworkBindings {

    private static final String CHANNEL = "mpmt:main";

    private BukkitNetworkBindings() {
        // 工具类不实例化
    }

    /** 构造所选锚点的服务端网络适配器。 */
    public static BukkitServerNetwork serverNetwork(
            SupportedVersion version, Plugin plugin, BukkitConnectionRegistry connections) {
        Objects.requireNonNull(version, "version 不能为空");
        switch (version) {
            case V1_20:
                return new V1_20BukkitServerNetwork(plugin, connections, CHANNEL);
            default:
                throw new IllegalStateException("缺少 Bukkit 服务端网络适配：" + version);
        }
    }
}
