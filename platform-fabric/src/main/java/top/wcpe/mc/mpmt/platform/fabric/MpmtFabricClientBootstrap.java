package top.wcpe.mc.mpmt.platform.fabric;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 客户端入口（client，仅客户端发行环境）：客户端侧初始化的接缝。
 *
 * <p>当前仅占位打通"双端入口分离"结构（FR-08）；客户端渲染 / HUD 等随后续特性增量。平台装配统一在
 * {@link MpmtFabricBootstrap}（main 入口、两端共用）一次性完成，客户端入口不重复装配。
 */
public final class MpmtFabricClientBootstrap implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    @Override
    public void onInitializeClient() {
        LOGGER.info("MPMT Fabric 客户端入口就绪");
    }
}
