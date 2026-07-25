package top.wcpe.mc.mpmt.platform.neoforge.capability;

import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeConnectionHandle;

/** NeoForge 连接控制端口：按 UUID 重新查询当前玩家并在服务端线程执行真实断开。 */
public final class NeoForgeConnectionControlPort implements ConnectionControlPort {

    private final MinecraftServer server;

    public NeoForgeConnectionControlPort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    @Override
    public EntityRef entityOf(ConnectionHandle connection) {
        return new EntityRef(handle(connection).playerId());
    }

    @Override
    public void disconnect(ConnectionHandle connection, String reason) {
        NeoForgeConnectionHandle handle = handle(connection);
        ServerPlayer current = server.getPlayerList().getPlayer(handle.playerId());
        if (current != null) {
            current.connection.disconnect(Component.literal(reason));
        }
    }

    private static NeoForgeConnectionHandle handle(ConnectionHandle connection) {
        return (NeoForgeConnectionHandle) connection;
    }
}
