package top.wcpe.mc.mpmt.platform.bukkit;

import top.wcpe.mc.mpmt.core.runtime.RuntimePorts;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * Bukkit 家族平台入口（SPI 实现），经 {@code META-INF/services} 注册供 ServiceLoader 发现。
 *
 * <p>端口实现随传输 / 调度 / 消息等特性增量注入；当前装配阶段暂无端口（用到才建，scope-discipline）。
 */
public final class BukkitPlatformBootstrap implements PlatformBootstrap {

    public BukkitPlatformBootstrap() {
        // ServiceLoader 需要公开无参构造
    }

    @Override
    public String platformId() {
        return "bukkit";
    }

    @Override
    public FeatureGate featureGate() {
        return new BukkitFeatureGate();
    }

    @Override
    public void assemble(RuntimePorts ports) {
        // 端口随后续特性（传输 / 调度 / 消息 / 持久化）增量注入；此处暂不注册
    }
}
