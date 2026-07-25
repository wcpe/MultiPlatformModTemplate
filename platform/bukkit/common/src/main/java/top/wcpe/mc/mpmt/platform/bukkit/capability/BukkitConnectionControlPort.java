package top.wcpe.mc.mpmt.platform.bukkit.capability;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionHandle;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionRegistry;

/** Bukkit 连接控制端口：按 UUID 重查当前在线玩家并执行真实踢出。 */
public final class BukkitConnectionControlPort implements ConnectionControlPort {

    private final Plugin plugin;
    private final BukkitConnectionRegistry connections;

    public BukkitConnectionControlPort(Plugin plugin, BukkitConnectionRegistry connections) {
        this.plugin = Objects.requireNonNull(plugin, "plugin 不能为空");
        this.connections = Objects.requireNonNull(connections, "connections 不能为空");
    }

    @Override
    public EntityRef entityOf(ConnectionHandle connection) {
        return new EntityRef(handle(connection).playerId());
    }

    @Override
    public void disconnect(ConnectionHandle connection, String reason) {
        BukkitConnectionHandle handle = handle(connection);
        Player player = plugin.getServer().getPlayer(handle.playerId());
        if (player != null
                && player.isOnline()
                && connections.isCurrent(handle, player)) {
            player.kickPlayer(reason);
        }
    }

    private static BukkitConnectionHandle handle(ConnectionHandle connection) {
        return (BukkitConnectionHandle) connection;
    }
}
