package top.wcpe.mc.mpmt.platform.forge.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 客户端代理：客户端发行环境下的端侧初始化接缝。
 *
 * <p>当前仅占位打通"client/server 分离代理"结构（FR-09）；客户端渲染 / HUD 等随后续特性增量。
 */
public final class ClientProxy implements SidedProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    @Override
    public void init() {
        LOGGER.info("MPMT Forge 客户端代理就绪");
    }
}
