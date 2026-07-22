package top.wcpe.mc.mpmt.platform.forge.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.PlayerPort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;

/** Forge 玩家查询端口：把服务端在线玩家转换为平台无关引用。 */
public final class ForgePlayerPort implements PlayerPort {

    private final MinecraftServer server;

    public ForgePlayerPort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    @Override
    public boolean isOnline(UUID playerId) {
        return server.getPlayerList().getPlayer(playerId) != null;
    }

    @Override
    public List<PlayerRef> onlinePlayers() {
        List<PlayerRef> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            players.add(toRef(player));
        }
        return Collections.unmodifiableList(players);
    }

    @Override
    public Optional<PlayerRef> resolve(UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player == null ? Optional.empty() : Optional.of(toRef(player));
    }

    private static PlayerRef toRef(ServerPlayer player) {
        return new PlayerRef(player.getUUID(), player.getName().getString());
    }
}
