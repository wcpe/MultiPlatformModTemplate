package top.wcpe.mc.mpmt.platform.fabric;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.domain.Mpmt;

/**
 * M0 阶段最小 Fabric 入口：仅引用 L0 核心常量，证明 core 已正确打入产物且可被加载。
 *
 * <p>本类不含任何平台胶水逻辑（SPI 装配 / TransportPort / 命令等属后续平台轮次）；
 * 存在的唯一目的是让产物成为一个可加载的合法 mod，并验证 core 依赖链路。
 */
public final class MpmtFabricBootstrap implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    @Override
    public void onInitialize() {
        // 引用 core 常量，证明共享核心已随产物加载；平台胶水后续实现
        LOGGER.info("MPMT 命名空间 {} 已加载（M0 构建骨架 / 打包 spike）", Mpmt.NAMESPACE);
    }
}
