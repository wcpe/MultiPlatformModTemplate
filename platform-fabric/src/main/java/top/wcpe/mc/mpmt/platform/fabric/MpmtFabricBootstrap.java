package top.wcpe.mc.mpmt.platform.fabric;

import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
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
        // 先装配平台端口（含服务端 TransportPort），再登记复用的服务端网络特性，最后启用
        PlatformProvider.boot(getClass().getClassLoader(), runtime);
        // 会话 id 暂用随机 UUID（每会话唯一即可）；统一生成策略待 FR-28 会话注册表整合
        runtime.features()
                .register(new ServerNetworkFeature(new BanRegistry(), () -> UUID.randomUUID().toString()));
        runtime.enable();
        LOGGER.info("MPMT 已装配并启用，活跃平台：{}", PlatformProvider.get().platformId());
    }
}
