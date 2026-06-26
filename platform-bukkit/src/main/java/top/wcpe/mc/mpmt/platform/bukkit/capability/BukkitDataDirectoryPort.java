package top.wcpe.mc.mpmt.platform.bukkit.capability;

import java.nio.file.Path;
import java.util.Objects;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;

/**
 * Bukkit 数据基目录端口（L3，FR-30）：以插件数据目录（{@code plugins/<插件名>/}）为基目录，
 * 共享层（core-paths / 持久化）在其下拼相对预设位置（ADR-0010）。
 */
public final class BukkitDataDirectoryPort implements DataDirectoryPort {

    private final Plugin plugin;

    public BukkitDataDirectoryPort(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin 不能为空");
    }

    @Override
    public Path baseDirectory() {
        return plugin.getDataFolder().toPath();
    }
}
