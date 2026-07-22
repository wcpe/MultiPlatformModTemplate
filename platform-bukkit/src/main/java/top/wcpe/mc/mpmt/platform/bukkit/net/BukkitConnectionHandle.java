package top.wcpe.mc.mpmt.platform.bukkit.net;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/** Bukkit 服务端物理连接句柄：仅保存 UUID，平台对象由执行时重新查询。 */
public final class BukkitConnectionHandle implements ConnectionHandle {

    private final UUID playerId;

    public BukkitConnectionHandle(Player player) {
        this(Objects.requireNonNull(player, "player 不能为空").getUniqueId());
    }

    public BukkitConnectionHandle(UUID playerId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId 不能为空");
    }

    /** 取得玩家 UUID，供平台层重查当前在线对象。 */
    public UUID playerId() {
        return playerId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BukkitConnectionHandle)) {
            return false;
        }
        return playerId.equals(((BukkitConnectionHandle) o).playerId);
    }

    @Override
    public int hashCode() {
        return playerId.hashCode();
    }
}
