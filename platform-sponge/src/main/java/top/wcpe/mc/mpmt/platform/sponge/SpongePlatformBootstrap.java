package top.wcpe.mc.mpmt.platform.sponge;

import top.wcpe.mc.mpmt.core.runtime.RuntimePorts;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * Sponge 平台入口（SPI 实现），经 {@code META-INF/services} 注册供 ServiceLoader 发现。
 *
 * <p>端口实现随传输 / 调度等特性增量注入；当前装配阶段暂无端口（用到才建，scope-discipline）。Sponge 为纯服务端
 * 平台（SpongeVanilla，无客户端插件 API），故 FR-27 跨端 HUD 由 Sponge 服<b>下发</b>、客户端复用我方 Fabric
 * 伴侣渲染（异构互通 FR-11②，同 Bukkit 模式）。平台标识 {@code sponge}。
 */
public final class SpongePlatformBootstrap implements PlatformBootstrap {

    public SpongePlatformBootstrap() {
        // ServiceLoader 需要公开无参构造
    }

    @Override
    public String platformId() {
        return "sponge";
    }

    @Override
    public FeatureGate featureGate() {
        return new SpongeFeatureGate();
    }

    @Override
    public void assemble(RuntimePorts ports) {
        // 端口随后续特性（传输 / 调度 / 消息）增量注入；此处暂不注册
    }
}
