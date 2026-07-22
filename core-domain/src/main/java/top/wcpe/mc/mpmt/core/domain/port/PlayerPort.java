package top.wcpe.mc.mpmt.core.domain.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;

/**
 * 玩家查询端口（L0）：只暴露平台无关的玩家引用与 UUID。
 *
 * <p>MVP 仅提供在线状态、在线列表与引用解析，不扩展背包、权限或其他玩家操作。
 */
public interface PlayerPort {

    /** 判断指定玩家当前是否在线。 */
    boolean isOnline(UUID playerId);

    /** 返回当前在线玩家的快照。 */
    List<PlayerRef> onlinePlayers();

    /** 把 UUID 解析为当前可用的玩家引用；不可解析时返回空。 */
    Optional<PlayerRef> resolve(UUID playerId);
}
