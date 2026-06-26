package top.wcpe.mc.mpmt.platform.neoforge;

import top.wcpe.mc.mpmt.core.runtime.RuntimePorts;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;
import top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap;

/**
 * NeoForge 平台入口（SPI 实现），经 {@code META-INF/services} 注册供 ServiceLoader 发现。
 *
 * <p>端口实现随传输 / 调度等特性增量注入；当前装配阶段暂无端口（用到才建，scope-discipline）。镜像 Forge 结构，
 * 平台标识为 {@code neoforge}（与 Forge 区分，进程级单一活跃绑定见 ADR-0008）。
 */
public final class NeoForgePlatformBootstrap implements PlatformBootstrap {

    public NeoForgePlatformBootstrap() {
        // ServiceLoader 需要公开无参构造
    }

    @Override
    public String platformId() {
        return "neoforge";
    }

    @Override
    public FeatureGate featureGate() {
        return new NeoForgeFeatureGate();
    }

    @Override
    public void assemble(RuntimePorts ports) {
        // 端口随后续特性（传输 / 调度 / 消息）增量注入；此处暂不注册
    }
}
