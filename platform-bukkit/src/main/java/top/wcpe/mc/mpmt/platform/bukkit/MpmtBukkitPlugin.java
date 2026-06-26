package top.wcpe.mc.mpmt.platform.bukkit;

import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.platform.bukkit.capability.BukkitCapabilityBootstrap;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitServerTransport;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * Bukkit 家族插件入口：进服后驱动平台装配——构造运行时、经本插件类加载器发现并装配唯一活跃平台、启用特性。
 *
 * <p>用本插件类加载器（PluginClassLoader）做 ServiceLoader 发现，确保扫到本 jar 内的 services（ADR-0002 注意项）。
 */
public class MpmtBukkitPlugin extends JavaPlugin {

    private MpmtRuntime runtime;

    @Override
    public void onEnable() {
        runtime = new MpmtRuntime();
        PlatformProvider.boot(getClass().getClassLoader(), runtime);
        // 服务端 TransportPort（FR-20）需 JavaPlugin 实例做插件消息，故在入口注册（SPI assemble 无平台上下文）
        runtime.ports().register(TransportPort.class, new BukkitServerTransport(this));
        // 登记平台无关的服务端网络特性（FR-19）：各平台注入自己的 TransportPort 即复用同一份装配
        runtime.features()
                .register(new ServerNetworkFeature(new BanRegistry(), () -> UUID.randomUUID().toString()));
        runtime.enable();
        // 平台能力示例（FR-26）：装配 L3 端口 + 桥接玩家进退事件入运行时自有 EventBus
        BukkitCapabilityBootstrap.register(this, runtime.eventBus());
        getLogger().info("MPMT 已装配并启用，活跃平台：" + PlatformProvider.get().platformId());
    }

    @Override
    public void onDisable() {
        if (runtime != null && runtime.phase() == MpmtRuntime.Phase.ENABLED) {
            runtime.disable();
        }
    }
}
