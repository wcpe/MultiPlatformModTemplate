package top.wcpe.mc.mpmt.platform.bukkit.version;

import java.util.Objects;
import org.bukkit.plugin.Plugin;

/** Bukkit 家族运行期版本探测。 */
public final class BukkitVersions {

    private BukkitVersions() {
        // 工具类不实例化
    }

    /** 从实际 Bukkit 服务端 API 读取 Minecraft 版本并选择锚点。 */
    public static SupportedVersion detect(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin 不能为空");
        return SupportedVersion.match(plugin.getServer().getMinecraftVersion());
    }
}
