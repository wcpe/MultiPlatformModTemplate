package top.wcpe.mc.mpmt.platform.spi;

/**
 * 平台 / 版本能力枚举：{@link FeatureGate} 探测的对象，承载"特判"的能力维度（ADR-0002 / ADR-0003）。
 *
 * <p>用枚举而非散落字符串 / if-else（反模式禁令）。能力随特性增量添加——此处仅列已被 ADR 锁定的 MVP 能力。
 */
public enum Capability {

    /** Folia 区域调度可用；否则回落到全局主线程调度（ADR-0013 / FR-13）。 */
    REGION_SCHEDULER,

    /** 融合服：同进程 Forge 与 Bukkit 共存（CatServer 等，ADR-0008 / FR-25）。 */
    HYBRID_FORGE_BUKKIT,

    /** 集成服（单人世界）内存回环可用（FR-20）。 */
    INTEGRATED_SERVER
}
