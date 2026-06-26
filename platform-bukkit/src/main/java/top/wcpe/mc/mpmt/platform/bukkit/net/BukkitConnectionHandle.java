package top.wcpe.mc.mpmt.platform.bukkit.net;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/**
 * Bukkit 服务端连接句柄（L3）：把平台原生 {@link Player} 封装为 L0/L1 只见的不透明 {@link ConnectionHandle}。
 *
 * <p>平台原生对象不泄漏进 L0/L1（ADR-0001）——上层只持有本句柄，发送时由 L3 传输解封取回 {@link Player}。
 * 以玩家 UUID 定义相等性，确保同一玩家的句柄在会话 / 派发表中作键一致。
 *
 * <p><b>重连约束</b>：玩家断线重连后是<b>新的</b> {@link Player} 实例（同 UUID）。本句柄按 UUID 相等，
 * 故作表键稳定；缓存的旧句柄其 {@code player} 字段指向已失效连接，会话整合（FR-21/28）发送时须按 UUID 取最新句柄。
 */
public final class BukkitConnectionHandle implements ConnectionHandle {

    private final Player player;
    private final UUID playerId;

    public BukkitConnectionHandle(Player player) {
        this.player = Objects.requireNonNull(player, "player 不能为空");
        this.playerId = player.getUniqueId();
    }

    /** 取回原生玩家对象（仅 L3 内部使用）。 */
    public Player player() {
        return player;
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
