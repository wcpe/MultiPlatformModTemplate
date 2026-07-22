package top.wcpe.mc.mpmt.platform.sponge;

import java.util.Objects;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionHandle;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionRegistry;

/** Sponge 玩家进退服到服务端网络特性的原生事件桥。 */
final class SpongeServerConnectionListener {

    private final ServerNetworkFeature networkFeature;
    private final SpongeConnectionRegistry connections;

    SpongeServerConnectionListener(
            ServerNetworkFeature networkFeature, SpongeConnectionRegistry connections) {
        this.networkFeature = Objects.requireNonNull(networkFeature, "networkFeature 不能为空");
        this.connections = Objects.requireNonNull(connections, "connections 不能为空");
    }

    @Listener
    public void onJoin(ServerSideConnectionEvent.Join event) {
        networkFeature.onConnected(connections.connected(event.player()));
    }

    @Listener
    public void onDisconnect(ServerSideConnectionEvent.Disconnect event) {
        SpongeConnectionHandle handle = connections.disconnected(event.player());
        if (handle != null) {
            networkFeature.onDisconnected(handle);
        }
    }
}
