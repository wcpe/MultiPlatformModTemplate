package top.wcpe.mc.mpmt.platform.bukkit;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionHandle;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionRegistry;

/** Bukkit 玩家进退服到服务端网络特性的原生事件桥。 */
final class BukkitServerConnectionListener implements Listener {

    private final ServerNetworkFeature networkFeature;
    private final BukkitConnectionRegistry connections;

    BukkitServerConnectionListener(
            ServerNetworkFeature networkFeature, BukkitConnectionRegistry connections) {
        this.networkFeature = Objects.requireNonNull(networkFeature, "networkFeature 不能为空");
        this.connections = Objects.requireNonNull(connections, "connections 不能为空");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        networkFeature.onConnected(connections.connected(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BukkitConnectionHandle handle = connections.disconnected(event.getPlayer());
        if (handle != null) {
            networkFeature.onDisconnected(handle);
        }
    }
}
