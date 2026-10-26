package top.wcpe.mc.mpmt.examples.counter;

import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/**
 * 客户端展示契约（示范域 counter，仅客户端可见）。
 *
 * <p>放在 {@code client-api}：服务端车道不引用它，避免把客户端契约带进服务端编译面（ADR-0030）。
 */
public interface CounterDisplayPort {

    /**
     * 展示该玩家的累计加入次数（HUD / 聊天等由实现决定）。
     *
     * @param player 目标玩家
     * @param count  累计次数
     */
    void showJoinCount(PlayerRef player, int count);
}
