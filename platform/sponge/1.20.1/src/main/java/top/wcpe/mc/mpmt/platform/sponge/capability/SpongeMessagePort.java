package top.wcpe.mc.mpmt.platform.sponge.capability;

import net.kyori.adventure.text.Component;
import org.spongepowered.api.Sponge;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;

/**
 * Sponge 消息端口（L3，FR-26）：按 {@link PlayerRef} 的 UUID 找在线玩家，发聊天栏消息（adventure 组件）。
 * 玩家不在线则静默丢弃。调用方须经 SchedulerPort 按归属切到主线程后再调（ADR-0013）。
 */
public final class SpongeMessagePort implements MessagePort {

    @Override
    public void send(PlayerRef player, String text) {
        Sponge.server()
                .player(player.getUuid())
                .ifPresent(target -> target.sendMessage(Component.text(text)));
    }
}
