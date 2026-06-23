package top.wcpe.mc.mpmt.platform.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.forge.proxy.ClientProxy;
import top.wcpe.mc.mpmt.platform.forge.proxy.ServerProxy;
import top.wcpe.mc.mpmt.platform.forge.proxy.SidedProxy;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * Forge mod 入口（{@code @Mod}）：构造期驱动平台装配（两端共用），再按运行端选择分离代理初始化。
 *
 * <p>用本类的类加载器（Forge mod 加载器）做 ServiceLoader 发现，确保扫到本 mod 的 services（ADR-0002 注意项）。
 */
@Mod("mpmt")
public final class MpmtForgeMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    public MpmtForgeMod() {
        MpmtRuntime runtime = new MpmtRuntime();
        // 通用装配（client/server 两端共用）：发现并装配唯一活跃平台、启用特性
        PlatformProvider.boot(getClass().getClassLoader(), runtime);
        runtime.enable();
        // client/server 分离代理：按运行端选择并初始化（FR-09）
        SidedProxy proxy = FMLEnvironment.dist == Dist.CLIENT ? new ClientProxy() : new ServerProxy();
        proxy.init();
        LOGGER.info("MPMT 已装配并启用，活跃平台：{}", PlatformProvider.get().platformId());
    }
}
