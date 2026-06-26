package top.wcpe.mc.mpmt.platform.neoforge;

import java.util.UUID;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeServerTransport;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * NeoForge mod 入口（{@code @Mod}）：构造期驱动平台装配——发现并装配唯一活跃平台、注册产品传输、启用运行时。
 *
 * <p>用本类的类加载器（NeoForge mod 加载器）做 ServiceLoader 发现，确保扫到本 mod 的 services（ADR-0002 注意项）。
 * 产品传输用 NeoForge {@link NeoForgeServerTransport}（SimpleChannel），通道在其构造期注册（NetworkRegistry 锁定前），
 * 故无需订阅事件。客户端 HUD 收包（FR-27）与分离代理随后续里程碑增量。
 */
@Mod("mpmt")
public final class MpmtNeoForgeMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    /**
     * 活跃产品传输 Holder（启动期一次性装配、之后只读，ADR-0002）：供同进程内的<b>验收驱动 mod</b> 经产品通道发
     * 服务端→客户端字节（如冒烟场景的跨端 HUD，FR-27）。
     */
    private static volatile NeoForgeServerTransport activeTransport;

    public MpmtNeoForgeMod() {
        MpmtRuntime runtime = new MpmtRuntime();
        // 通用装配：发现并装配唯一活跃平台（进程级单一活跃绑定见 ADR-0008 / FR-25）
        PlatformProvider.boot(getClass().getClassLoader(), runtime);
        // 服务端 TransportPort（FR-20）：NeoForge SimpleChannel 产品通道 mpmt:main，构造期建链路
        NeoForgeServerTransport transport = new NeoForgeServerTransport("mpmt", "main");
        activeTransport = transport;
        runtime.ports().register(TransportPort.class, transport);
        // 登记平台无关的服务端网络特性（FR-19）：注入 TransportPort 即复用同一份握手 / 协商 / 收发装配
        runtime.features()
                .register(new ServerNetworkFeature(new BanRegistry(), () -> UUID.randomUUID().toString()));
        runtime.enable();
        LOGGER.info("MPMT 已装配并启用，活跃平台：{}", PlatformProvider.get().platformId());
    }

    /** 取活跃产品传输（验收驱动经其发跨端字节）；启动期装配后非空。 */
    public static NeoForgeServerTransport activeTransport() {
        return activeTransport;
    }
}
