package top.wcpe.mc.mpmt.platform.sponge.net;

import java.util.Objects;
import java.util.UUID;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/**
 * Sponge 服务端连接句柄（L3）：以玩家 UUID 把连接封装为 L0/L1 只见的不透明 {@link ConnectionHandle}。
 *
 * <p>平台原生对象（{@code ServerPlayer}）不泄漏进 L0/L1（ADR-0001）——本句柄仅持 UUID，
 * 发送时由 L3 传输按 UUID 取回当前在线玩家。以 UUID 定义相等性，确保同一玩家的句柄在会话 / 派发表中作键一致。
 *
 * <p><b>重连约束</b>：玩家断线重连后是<b>新的</b> {@code ServerPlayer} 实例（同 UUID）。本句柄只存 UUID、
 * 不缓存玩家实例，故作表键稳定、且发送恒取最新连接（优于缓存实例，天然规避失效引用）。
 */
public final class SpongeConnectionHandle implements ConnectionHandle {

    private final UUID playerId;

    public SpongeConnectionHandle(UUID playerId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId 不能为空");
    }

    /** 取玩家 UUID（仅 L3 内部用于按 UUID 取回在线玩家）。 */
    public UUID playerId() {
        return playerId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SpongeConnectionHandle)) {
            return false;
        }
        return playerId.equals(((SpongeConnectionHandle) o).playerId);
    }

    @Override
    public int hashCode() {
        return playerId.hashCode();
    }
}
