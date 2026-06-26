package top.wcpe.mc.mpmt.platform.forge.net;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/**
 * Forge 服务端连接句柄（L3）：把平台原生 {@link ServerPlayer} 封装为 L0/L1 只见的不透明 {@link ConnectionHandle}。
 *
 * <p>平台原生对象不泄漏进 L0/L1（ADR-0001）——上层只持有本句柄，发送时由 L3 传输解封取回 {@link ServerPlayer}。
 * 以玩家 UUID 定义相等性，确保同一玩家的句柄在会话 / 派发表中作键一致；重连后同 UUID 取最新句柄（FR-21/28）。
 */
public final class ForgeConnectionHandle implements ConnectionHandle {

    private final ServerPlayer player;
    private final UUID playerId;

    public ForgeConnectionHandle(ServerPlayer player) {
        this.player = Objects.requireNonNull(player, "player 不能为空");
        this.playerId = player.getUUID();
    }

    /** 取回原生玩家对象（仅 L3 内部使用）。 */
    public ServerPlayer player() {
        return player;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ForgeConnectionHandle)) {
            return false;
        }
        return playerId.equals(((ForgeConnectionHandle) o).playerId);
    }

    @Override
    public int hashCode() {
        return playerId.hashCode();
    }
}
