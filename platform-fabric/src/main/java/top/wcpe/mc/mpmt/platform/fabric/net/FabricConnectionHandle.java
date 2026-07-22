package top.wcpe.mc.mpmt.platform.fabric.net;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/**
 * Fabric 服务端连接句柄（L3）：把平台原生 {@link ServerPlayer} 封装为 L0/L1 只见的不透明 {@link ConnectionHandle}。
 *
 * <p>平台原生对象不泄漏进 L0/L1（ADR-0001）——上层只持有本句柄，发送时由 L4 绑定解封取回 {@link ServerPlayer}。
 * 以玩家 UUID 定义相等性，确保同一玩家的句柄在会话 / 派发表中作键一致。
 *
 * <p><b>重连约束</b>：玩家断线重连后是<b>新的</b> {@link ServerPlayer} 实例（同 UUID）。本句柄按 UUID 相等，
 * 故作表键稳定；但缓存的旧句柄其 {@code player} 字段指向已失效连接。会话整合（FR-21/28）发送时须按 UUID
 * 取<b>最新</b>句柄，勿用缓存旧句柄直接发送。
 */
public final class FabricConnectionHandle implements ConnectionHandle {

    private final ServerPlayer player;
    private final UUID playerId;

    public FabricConnectionHandle(ServerPlayer player) {
        this.player = Objects.requireNonNull(player, "player 不能为空");
        this.playerId = player.getUUID();
    }

    /** 取回原生玩家对象（仅 L3/L4 内部使用）。 */
    public ServerPlayer player() {
        return player;
    }

    /** 玩家 UUID，用于断开前重新查询当前在线实体。 */
    public UUID playerId() {
        return playerId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FabricConnectionHandle)) {
            return false;
        }
        return playerId.equals(((FabricConnectionHandle) o).playerId);
    }

    @Override
    public int hashCode() {
        return playerId.hashCode();
    }
}
