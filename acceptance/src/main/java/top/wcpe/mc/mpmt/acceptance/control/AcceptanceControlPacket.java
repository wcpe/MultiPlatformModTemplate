package top.wcpe.mc.mpmt.acceptance.control;

/**
 * 验收测试控制包标记接口（ADR-0014）：服务端驱动 / 客户端验证联调用的<b>测试控制协议</b>。
 *
 * <p><b>独立于产品协议</b>：手写 codec、仅测试设施作用域，不入任何产品 jar / 产品协议（避免测试代码污染产品）。
 * 三包：{@link ClientReadyPacket}(C2S) / {@link RunStepPacket}(S2C) / {@link StepResultPacket}(C2S)。
 */
public interface AcceptanceControlPacket {
}
