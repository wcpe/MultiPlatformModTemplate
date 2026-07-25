package top.wcpe.mc.mpmt.platform.sponge.capability;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.spongepowered.api.Sponge;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionHandle;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionRegistry;

/** Sponge 连接控制端口：按 UUID 重查当前在线玩家并执行真实踢出。 */
public final class SpongeConnectionControlPort implements ConnectionControlPort {

    private final SpongeConnectionRegistry connections;

    public SpongeConnectionControlPort(SpongeConnectionRegistry connections) {
        this.connections = Objects.requireNonNull(connections, "connections 不能为空");
    }

    @Override
    public EntityRef entityOf(ConnectionHandle connection) {
        return new EntityRef(handle(connection).playerId());
    }

    @Override
    public void disconnect(ConnectionHandle connection, String reason) {
        SpongeConnectionHandle handle = handle(connection);
        Sponge.server()
                .player(handle.playerId())
                .filter(player -> connections.isCurrent(handle, player))
                .ifPresent(player -> player.kick(Component.text(reason)));
    }

    private static SpongeConnectionHandle handle(ConnectionHandle connection) {
        return (SpongeConnectionHandle) connection;
    }
}
