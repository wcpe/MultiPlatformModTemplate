package top.wcpe.mc.mpmt.platform.forge.capability;

import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;

/** Forge 消息端口：按玩家 UUID 向当前在线玩家发送系统消息。 */
public final class ForgeMessagePort implements MessagePort {

    private final MinecraftServer server;

    public ForgeMessagePort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    @Override
    public void send(PlayerRef player, String text) {
        ServerPlayer target = server.getPlayerList().getPlayer(player.getUuid());
        if (target != null) {
            target.sendSystemMessage(Component.literal(text));
        }
    }
}
