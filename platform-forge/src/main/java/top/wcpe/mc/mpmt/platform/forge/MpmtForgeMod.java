package top.wcpe.mc.mpmt.platform.forge;

import java.util.UUID;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeServerTransport;
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

    /**
     * 活跃产品传输 Holder（启动期一次性装配、之后只读，ADR-0002 Holder 形态）：供同进程内的<b>验收驱动 mod</b>
     * 经产品通道发服务端→客户端字节（如冒烟场景的跨端 HUD，FR-27），不必硬造 SimpleChannel 帧字节。
     */
    private static volatile ForgeServerTransport activeTransport;

    public MpmtForgeMod() {
        MpmtRuntime runtime = new MpmtRuntime();
        // 通用装配（client/server 两端共用）：发现并装配唯一活跃平台
        PlatformProvider.boot(getClass().getClassLoader(), runtime);
        // 服务端 TransportPort（FR-20）：Forge SimpleChannel 产品通道 mpmt:main，构造期建链路
        ForgeServerTransport transport = new ForgeServerTransport("mpmt", "main");
        activeTransport = transport;
        runtime.ports().register(TransportPort.class, transport);
        // 登记平台无关的服务端网络特性（FR-19）：注入 TransportPort 即复用同一份握手 / 协商 / 收发装配
        runtime.features()
                .register(new ServerNetworkFeature(new BanRegistry(), () -> UUID.randomUUID().toString()));
        runtime.enable();
        // client/server 分离代理：按运行端选择并初始化（FR-09）。客户端代理向产品传输注入 HUD 收包处理器
        // （FR-27）：ClientProxy 仅在 Dist.CLIENT 实例化，客户端专有类不被服务端加载。
        SidedProxy proxy =
                FMLEnvironment.dist == Dist.CLIENT
                        ? new ClientProxy(transport)
                        : new ServerProxy();
        proxy.init();
        LOGGER.info("MPMT 已装配并启用，活跃平台：{}", PlatformProvider.get().platformId());
    }

    /** 取活跃产品传输（验收驱动经其发跨端字节）；启动期装配后非空。 */
    public static ForgeServerTransport activeTransport() {
        return activeTransport;
    }
}
