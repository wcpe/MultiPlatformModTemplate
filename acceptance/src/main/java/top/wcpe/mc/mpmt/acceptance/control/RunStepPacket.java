package top.wcpe.mc.mpmt.acceptance.control;

import lombok.NonNull;
import lombok.Value;

/** S2C：服务端给客户端排程一步验证（{@code seq} 用于与回报的 {@link StepResultPacket} 对账）。 */
@Value
public class RunStepPacket implements AcceptanceControlPacket {

    /** 场景 id（与服务端场景一致）。 */
    @NonNull
    String scenarioId;

    /** 场景内步骤 id。 */
    @NonNull
    String stepId;

    /** 泛化参数（JSON 字符串，per-step 数据）。 */
    @NonNull
    String paramsJson;

    /** 序号，与回报 StepResult 的 seq 对账。 */
    int seq;
}
