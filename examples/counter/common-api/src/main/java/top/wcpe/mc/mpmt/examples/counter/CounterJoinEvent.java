package top.wcpe.mc.mpmt.examples.counter;

import lombok.NonNull;
import lombok.Value;
import top.wcpe.mc.mpmt.kernel.event.DomainEvent;
import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/**
 * 玩家加入计数域事件（示范域 counter 的共用契约）。
 *
 * <p>放在 {@code common-api}：client 与 server 都要消费它，属于对外稳定契约（ADR-0030）。
 * 平台 L3 把原生加入事件适配成本事件后投递到自有 EventBus，域只经 EventBus 协作（ADR-0011）。
 */
@Value
public class CounterJoinEvent implements DomainEvent {

    @NonNull
    PlayerRef player;

    /** 该玩家累计加入次数（含本次）。 */
    int count;
}
