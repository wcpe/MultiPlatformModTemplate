package top.wcpe.mc.mpmt.domain.capability;

import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/**
 * 服务端计数快照读取接口（capability 域的内部抽象）。
 *
 * <p>放在 {@code server-internal}：只声明"怎么取快照"，不含读取实现（ADR-0030 决策 5）。
 * 实现放 {@code server}；域内其它模块依赖本接口即可，不必知道存储形态。
 */
public interface CapabilitySnapshotReader {

    /**
     * 读取该玩家的计数快照。
     *
     * @param player 目标玩家
     * @return 快照；无历史记录时次数为 0、首次加入时间为 0
     */
    CapabilitySnapshot read(PlayerRef player);
}
