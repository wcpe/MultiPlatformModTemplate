package top.wcpe.mc.mpmt.domain.capability;

import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/**
 * 首次加入时间查询契约（capability 域对外发布的服务端抽象）。
 *
 * <p>放在 {@code server-api}：这是**对外**发布面，调用方只依赖本接口，
 * 不知道存储形态与实现细节（ADR-0030 决策 1/5）。
 */
public interface CapabilityQueryPort {

    /**
     * 读取该玩家的首次加入时间。
     *
     * @param player 目标玩家
     * @return 首次加入时间（毫秒）；从未记录过返回 0
     */
    long firstJoinMillis(PlayerRef player);
}
