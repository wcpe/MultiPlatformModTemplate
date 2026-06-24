package top.wcpe.mc.mpmt.domain.capability;

import lombok.NonNull;
import lombok.Value;
import top.wcpe.mc.mpmt.core.domain.event.DomainEvent;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;

/**
 * 玩家加入领域事件（功能域 capability）：由各平台 L3 把原生加入事件适配后投递到自有 EventBus。
 *
 * <p>功能域只经 EventBus 协作、不直接依赖兄弟域（ADR-0011）。
 */
@Value
public class PlayerJoinedEvent implements DomainEvent {

    @NonNull
    PlayerRef player;
}
