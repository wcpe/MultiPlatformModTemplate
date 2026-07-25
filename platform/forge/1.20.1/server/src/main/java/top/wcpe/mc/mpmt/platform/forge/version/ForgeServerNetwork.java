package top.wcpe.mc.mpmt.platform.forge.version;

import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/** Forge 服务端网络版本适配接口：只承载版本敏感的通道注册与收发。 */
public interface ForgeServerNetwork {

    /** 产品通道资源位置。 */
    ResourceLocation channelId();

    /** 注册服务端裸字节入站回调。 */
    void registerReceiver(BiConsumer<ServerPlayer, byte[]> handler);

    /** 向指定玩家发送裸字节。 */
    void send(ServerPlayer player, byte[] data);

    /** 把原生玩家转换为连接句柄（不持有连接表）。 */
    ConnectionHandle connectionOf(ServerPlayer player);

    /** 当前版本单包载荷上限。 */
    int maxPayloadSize();
}
