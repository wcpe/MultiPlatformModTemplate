package top.wcpe.mc.mpmt.platform.spi;

/**
 * 能力探测：平台无关代码问"当前平台 / 版本是否具备某能力"，据此分流，而非硬编码平台 / 版本 if-else。
 *
 * <p>探测"能干什么"而非"你是谁"（capability detection，非平台嗅探）。与端口配合——端口是"要某能力"的统一调用面，
 * FeatureGate 是"当前环境有没有该能力"的判定。各平台 L3 实现本接口（ADR-0002 §FeatureGate）。
 */
public interface FeatureGate {

    /** 当前平台 / 版本是否支持某能力。 */
    boolean supports(Capability capability);
}
