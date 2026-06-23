package top.wcpe.mc.mpmt.platform.fabric;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * Fabric 主入口（main，客户端与服务端发行环境共用）：驱动平台装配——构造运行时、经本 mod 类加载器
 * 发现并装配唯一活跃平台、启用特性。
 *
 * <p>用本类的类加载器（Fabric knot 加载器）做 ServiceLoader 发现，确保扫到本 mod 的 services（ADR-0002 注意项）。
 */
public final class MpmtFabricBootstrap implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    @Override
    public void onInitialize() {
        MpmtRuntime runtime = new MpmtRuntime();
        // 玩法特性随后续增量登记到 runtime.features()；当前先打通"发现 + 装配 + 启用"链路
        PlatformProvider.boot(getClass().getClassLoader(), runtime);
        runtime.enable();
        LOGGER.info("MPMT 已装配并启用，活跃平台：{}", PlatformProvider.get().platformId());
    }
}
