package top.wcpe.mc.mpmt.platform.forge.capability;

import java.util.Objects;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import top.wcpe.mc.mpmt.kernel.port.MessagePort;
import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/** Forge 1.12.2 消息端口：按玩家 UUID 向当前在线玩家发送系统消息（聊天栏）。 */
public final class Forge112MessagePort implements MessagePort {

    private final MinecraftServer server;

    /**
     * 构造。
     *
     * @param server 当前服务端实例，不可为空
     */
    public Forge112MessagePort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    @Override
    public void send(PlayerRef player, String text) {
        EntityPlayerMP target = server.getPlayerList().getPlayerByUUID(player.getUuid());
        if (target != null) {
            target.sendMessage(new TextComponentString(text));
        }
    }
}
