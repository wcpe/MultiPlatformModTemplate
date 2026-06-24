package top.wcpe.mc.mpmt.acceptance.control;

import lombok.NonNull;
import lombok.Value;

/** C2S：客户端回报某步验证结果（{@code seq} 与下发的 {@link RunStepPacket} 对账）。 */
@Value
public class StepResultPacket implements AcceptanceControlPacket {

    /** 场景 id。 */
    @NonNull
    String scenarioId;

    /** 步骤 id。 */
    @NonNull
    String stepId;

    /** 序号，与下发 RunStep 的 seq 对账。 */
    int seq;

    /** 结果状态。 */
    @NonNull
    StepStatus status;

    /** 客户端断言数据（JSON 字符串）。 */
    @NonNull
    String resultJson;

    /** 诊断信息。 */
    @NonNull
    String message;
}
