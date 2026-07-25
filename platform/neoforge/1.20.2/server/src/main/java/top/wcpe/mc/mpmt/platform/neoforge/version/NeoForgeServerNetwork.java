package top.wcpe.mc.mpmt.platform.neoforge.version;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/** NeoForge 服务端网络版本适配接口：只承载版本敏感的通道注册与收发。 */
public interface NeoForgeServerNetwork {

    /** 注册服务端入站回调（原生玩家 + 裸字节）。 */
    void registerReceiver(BiConsumer<ServerPlayer, byte[]> handler);

    /** 注入客户端收包处理器。 */
    void setClientReceiver(Consumer<byte[]> receiver);

    /** 客户端向服务端发送裸字节。 */
    void sendToServer(byte[] data);

    /** 向指定玩家发送裸字节。 */
    void send(ServerPlayer player, byte[] data);

    /** 把原生玩家转换为连接句柄（不持有连接表）。 */
    ConnectionHandle connectionOf(ServerPlayer player);

    /** 当前版本单包载荷上限。 */
    int maxPayloadSize();
}
