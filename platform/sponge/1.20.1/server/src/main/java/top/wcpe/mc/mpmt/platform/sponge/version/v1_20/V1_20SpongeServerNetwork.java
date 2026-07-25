package top.wcpe.mc.mpmt.platform.sponge.version.v1_20;

import java.util.Objects;
import java.util.function.BiConsumer;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.network.ServerPlayerConnection;
import org.spongepowered.api.network.channel.ChannelBuf;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.plugin.PluginContainer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionHandle;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionRegistry;
import top.wcpe.mc.mpmt.platform.sponge.version.SpongeServerNetwork;

/** SpongeVanilla RC1365 的 1.20.1 原始数据通道适配器。 */
public final class V1_20SpongeServerNetwork implements SpongeServerNetwork {

    private static final int MAX_PAYLOAD = 32767;

    private final RawDataChannel channel;
    private final PluginContainer plugin;
    private final SpongeConnectionRegistry connections;
    private volatile BiConsumer<ConnectionHandle, byte[]> receiver;

    public V1_20SpongeServerNetwork(
            org.spongepowered.api.event.lifecycle.RegisterChannelEvent event,
            PluginContainer plugin,
            SpongeConnectionRegistry connections,
            ResourceKey channelKey) {
        this.plugin = Objects.requireNonNull(plugin, "plugin 不能为空");
        this.connections = Objects.requireNonNull(connections, "connections 不能为空");
        ResourceKey key = Objects.requireNonNull(channelKey, "channelKey 不能为空");
        this.channel = Objects.requireNonNull(event, "event 不能为空").register(key, RawDataChannel.class);
        channel.play().addHandler(ServerPlayerConnection.class, this::handle);
    }

    @Override
    public void registerReceiver(BiConsumer<ConnectionHandle, byte[]> handler) {
        this.receiver = Objects.requireNonNull(handler, "handler 不能为空");
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        SpongeConnectionHandle handle = (SpongeConnectionHandle) connection;
        if (!connections.isCurrent(handle)) {
            return;
        }
        if (Sponge.server().onMainThread()) {
            sendToPlayer(handle, data);
            return;
        }
        byte[] payload = data.clone();
        Sponge.server()
                .scheduler()
                .submit(Task.builder().execute(() -> sendToPlayer(handle, payload)).plugin(plugin).build());
    }

    @Override
    public int maxPayloadSize() {
        return MAX_PAYLOAD;
    }

    private void sendToPlayer(SpongeConnectionHandle handle, byte[] data) {
        if (!connections.isCurrent(handle)) {
            return;
        }
        Sponge.server()
                .player(handle.playerId())
                .filter(player -> connections.isCurrent(handle, player))
                .ifPresent(player -> channel.play().sendTo(player, buf -> buf.writeBytes(data)));
    }

    private void handle(ChannelBuf buf, ServerPlayerConnection connection) {
        BiConsumer<ConnectionHandle, byte[]> handler = receiver;
        if (handler == null) {
            return;
        }
        byte[] data = buf.readBytes(buf.available());
        handler.accept(connections.handleOf(connection.player()), data);
    }
}
