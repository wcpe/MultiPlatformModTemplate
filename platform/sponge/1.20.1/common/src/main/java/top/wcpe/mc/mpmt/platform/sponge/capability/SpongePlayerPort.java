package top.wcpe.mc.mpmt.platform.sponge.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.PlayerPort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;

/** Sponge 玩家查询端口：把在线玩家转换为平台无关引用。 */
public final class SpongePlayerPort implements PlayerPort {

    @Override
    public boolean isOnline(UUID playerId) {
        return Sponge.server().player(playerId).isPresent();
    }

    @Override
    public List<PlayerRef> onlinePlayers() {
        List<PlayerRef> players = new ArrayList<>();
        for (ServerPlayer player : Sponge.server().onlinePlayers()) {
            players.add(toRef(player));
        }
        return Collections.unmodifiableList(players);
    }

    @Override
    public Optional<PlayerRef> resolve(UUID playerId) {
        return Sponge.server().player(playerId).map(SpongePlayerPort::toRef);
    }

    private static PlayerRef toRef(ServerPlayer player) {
        return new PlayerRef(player.uniqueId(), player.name());
    }
}
