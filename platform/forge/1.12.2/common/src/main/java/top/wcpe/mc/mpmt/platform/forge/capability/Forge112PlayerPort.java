package top.wcpe.mc.mpmt.platform.forge.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import top.wcpe.mc.mpmt.kernel.port.PlayerPort;
import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/** Forge 1.12.2 玩家查询端口：把服务端在线玩家转换为平台无关引用。 */
public final class Forge112PlayerPort implements PlayerPort {

    private final MinecraftServer server;

    /**
     * 构造。
     *
     * @param server 当前服务端实例，不可为空
     */
    public Forge112PlayerPort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    @Override
    public boolean isOnline(UUID playerId) {
        return server.getPlayerList().getPlayerByUUID(playerId) != null;
    }

    @Override
    public List<PlayerRef> onlinePlayers() {
        List<PlayerRef> players = new ArrayList<>();
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            players.add(toRef(player));
        }
        return Collections.unmodifiableList(players);
    }

    @Override
    public Optional<PlayerRef> resolve(UUID playerId) {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        return player == null ? Optional.<PlayerRef>empty() : Optional.of(toRef(player));
    }

    private static PlayerRef toRef(EntityPlayerMP player) {
        return new PlayerRef(player.getUniqueID(), player.getName());
    }
}
