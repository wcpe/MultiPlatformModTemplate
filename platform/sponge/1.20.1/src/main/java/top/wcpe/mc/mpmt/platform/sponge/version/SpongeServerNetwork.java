package top.wcpe.mc.mpmt.platform.sponge.version;

import java.util.function.BiConsumer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/** Sponge 服务端网络版本适配接口。 */
public interface SpongeServerNetwork {

    /** 注册裸字节入站回调。 */
    void registerReceiver(BiConsumer<ConnectionHandle, byte[]> handler);

    /** 向指定物理连接发送裸字节。 */
    void send(ConnectionHandle connection, byte[] data);

    /** 返回当前版本的单包载荷上限。 */
    int maxPayloadSize();
}
