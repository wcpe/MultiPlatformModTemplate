package top.wcpe.mc.mpmt.core.domain.ref;

import lombok.NonNull;
import lombok.Value;

/**
 * 世界引用（L0 内核）：以标识串（世界名 / 维度 id）标识一个世界的平台无关领域视图。
 *
 * <p>用于 {@code SchedulerPort.runForLocation} 表达按方块 / 区域的调度<b>归属</b>——在 Folia 上落到该
 * 坐标所属区域的 {@code RegionScheduler}，其他平台退化为服务端主线程（ADR-0013）。
 */
@Value
public class WorldRef {

    @NonNull
    String id;
}
