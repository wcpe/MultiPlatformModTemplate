package top.wcpe.mc.mpmt.platform.bukkit.version.v1_20;

import java.util.Objects;
import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionHandle;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionRegistry;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitServerNetwork;

/** Bukkit 家族 1.20.1 插件消息网络适配器。 */
public final class V1_20BukkitServerNetwork
        implements BukkitServerNetwork, PluginMessageListener {

    private final Plugin plugin;
    private final BukkitConnectionRegistry connections;
    private final String channel;
    private volatile BiConsumer<ConnectionHandle, byte[]> receiver;

    public V1_20BukkitServerNetwork(
            Plugin plugin, BukkitConnectionRegistry connections, String channel) {
        this.plugin = Objects.requireNonNull(plugin, "plugin 不能为空");
        this.connections = Objects.requireNonNull(connections, "connections 不能为空");
        this.channel = Objects.requireNonNull(channel, "channel 不能为空");
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, channel);
        messenger.registerIncomingPluginChannel(plugin, channel, this);
    }

    @Override
    public void registerReceiver(BiConsumer<ConnectionHandle, byte[]> handler) {
        this.receiver = Objects.requireNonNull(handler, "handler 不能为空");
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        BukkitConnectionHandle handle = (BukkitConnectionHandle) connection;
        Player player = plugin.getServer().getPlayer(handle.playerId());
        if (player != null && player.isOnline() && connections.isCurrent(handle, player)) {
            player.sendPluginMessage(plugin, channel, data);
        }
    }

    @Override
    public int maxPayloadSize() {
        return Messenger.MAX_MESSAGE_SIZE;
    }

    @Override
    public void onPluginMessageReceived(String incomingChannel, Player player, byte[] message) {
        BiConsumer<ConnectionHandle, byte[]> handler = receiver;
        if (!channel.equals(incomingChannel) || handler == null) {
            return;
        }
        handler.accept(connections.handleOf(player), message);
    }
}
