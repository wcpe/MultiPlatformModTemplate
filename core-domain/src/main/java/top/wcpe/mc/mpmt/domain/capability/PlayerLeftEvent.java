package top.wcpe.mc.mpmt.domain.capability;

import lombok.NonNull;
import lombok.Value;
import top.wcpe.mc.mpmt.core.domain.event.DomainEvent;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;

/**
 * 玩家离开领域事件（功能域 capability）：由各平台 L3 把原生离开事件适配后投递到自有 EventBus。
 */
@Value
public class PlayerLeftEvent implements DomainEvent {

    @NonNull
    PlayerRef player;
}
