package top.wcpe.mc.mpmt.examples.counter;

import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/**
 * 加入次数读取端口（示范域 counter 的共用契约）。
 *
 * <p>放在 {@code common-api}：两端都要按玩家查次数，属于对外稳定契约（ADR-0030）。
 * 实现由 {@code server} 提供，客户端只面向本接口编程。
 */
public interface CounterQueryPort {

    /**
     * 读取该玩家的累计加入次数。
     *
     * @param player 目标玩家
     * @return 累计次数；从未加入过返回 0
     */
    int joinCount(PlayerRef player);
}
