package top.wcpe.mc.mpmt.platform.bukkit.capability;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;

/**
 * Bukkit 消息端口（L3，FR-26）：按 {@link PlayerRef} 的 UUID 找在线玩家，发聊天栏消息。
 * 玩家不在线则静默丢弃。调用方须经 SchedulerPort 按归属切到主线程后再调（ADR-0013）。
 */
public final class BukkitMessagePort implements MessagePort {

    @Override
    public void send(PlayerRef player, String text) {
        Player target = Bukkit.getPlayer(player.getUuid());
        if (target != null) {
            target.sendMessage(text);
        }
    }
}
