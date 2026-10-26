package top.wcpe.mc.mpmt.examples.counter;

import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/**
 * 服务端计数管理契约（示范域 counter，仅服务端可见）。
 *
 * <p>放在 {@code server-api}：客户端车道不引用它（ADR-0030）。
 */
public interface CounterAdminPort {

    /**
     * 重置该玩家的累计次数。
     *
     * @param player 目标玩家
     */
    void reset(PlayerRef player);
}
