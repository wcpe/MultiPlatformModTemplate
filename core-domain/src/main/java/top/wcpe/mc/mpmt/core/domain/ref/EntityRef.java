package top.wcpe.mc.mpmt.core.domain.ref;

import java.util.UUID;
import lombok.NonNull;
import lombok.Value;

/**
 * 实体引用（L0 内核）：以 UUID 标识一个实体的平台无关领域视图。
 *
 * <p>用于 {@code SchedulerPort.runForEntity} 表达调度<b>归属</b>——在 Folia 上落到该实体所属的
 * {@code EntityScheduler}，其他平台退化为服务端主线程（ADR-0013）。不携带任何平台原生对象。
 */
@Value
public class EntityRef {

    @NonNull
    UUID id;
}
