package top.wcpe.mc.mpmt.platform.bukkit.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.wcpe.mc.mpmt.core.domain.port.PlayerPort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;

/** Bukkit 玩家查询端口：把在线玩家转换为平台无关引用。 */
public final class BukkitPlayerPort implements PlayerPort {

    @Override
    public boolean isOnline(UUID playerId) {
        return Bukkit.getPlayer(playerId) != null;
    }

    @Override
    public List<PlayerRef> onlinePlayers() {
        List<PlayerRef> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.add(toRef(player));
        }
        return Collections.unmodifiableList(players);
    }

    @Override
    public Optional<PlayerRef> resolve(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player == null ? Optional.empty() : Optional.of(toRef(player));
    }

    private static PlayerRef toRef(Player player) {
        return new PlayerRef(player.getUniqueId(), player.getName());
    }
}
