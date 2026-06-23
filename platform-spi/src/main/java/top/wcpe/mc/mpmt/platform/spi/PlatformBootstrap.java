package top.wcpe.mc.mpmt.platform.spi;

import top.wcpe.mc.mpmt.core.runtime.RuntimePorts;

/**
 * 平台入口 SPI：每个平台胶水实现它，并经 {@code META-INF/services} 注册供 {@link java.util.ServiceLoader} 发现（ADR-0002）。
 *
 * <p>新增平台 = 实现本接口 + 注册一行 services，不改公共层（开闭）。
 */
public interface PlatformBootstrap {

    /** 平台标识（用于日志与冲突诊断，如 "bukkit" / "fabric" / "forge"）。 */
    String platformId();

    /** 本平台的能力探测器。 */
    FeatureGate featureGate();

    /**
     * 装配：把本平台构造的端口实现注册进运行时端口表。
     *
     * <p>仅注册端口，不在此写玩法逻辑（玩法在 L0/L1 特性里）。装配在启动期一次性完成。
     */
    void assemble(RuntimePorts ports);
}
