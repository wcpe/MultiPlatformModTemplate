package top.wcpe.mc.mpmt.platform.neoforge.capability;

import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;

/**
 * NeoForge 消息端口（L3，FR-26）：按 {@link PlayerRef} 的 UUID 找在线 {@link ServerPlayer}，发系统聊天消息。
 * 玩家不在线则静默丢弃。调用方须经 SchedulerPort 按归属切到主线程后再调（ADR-0013）。
 *
 * <p>NeoForge 运行期官方 Mojmap，类型与方法与 Fabric 实现一致（{@code getPlayerList().getPlayer(uuid)} /
 * {@code sendSystemMessage} / {@code Component.literal}）。
 */
public final class NeoForgeMessagePort implements MessagePort {

    private final MinecraftServer server;

    public NeoForgeMessagePort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    @Override
    public void send(PlayerRef player, String text) {
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUuid());
        if (serverPlayer != null) {
            serverPlayer.sendSystemMessage(Component.literal(text));
        }
    }
}
