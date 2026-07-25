package top.wcpe.mc.mpmt.platform.forge.modern.net;

import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/** 按底层 ServerPlayer 对象身份判等的 Forge 服务端连接句柄。 */
public final class ForgeConnectionHandle implements ConnectionHandle {

    private final ServerPlayer player;

    public ForgeConnectionHandle(ServerPlayer player) {
        this.player = Objects.requireNonNull(player, "玩家不能为空");
    }

    public ServerPlayer player() {
        return player;
    }

    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ForgeConnectionHandle handle)) {
            return false;
        }
        return player == handle.player;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(player);
    }
}
