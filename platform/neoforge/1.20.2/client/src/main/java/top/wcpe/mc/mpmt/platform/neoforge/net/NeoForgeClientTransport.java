package top.wcpe.mc.mpmt.platform.neoforge.net;

import java.util.Objects;
import java.util.function.BiConsumer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/** NeoForge 客户端传输：复用产品 SimpleChannel，并接入统一 ClientNetworkFeature。 */
@OnlyIn(Dist.CLIENT)
public final class NeoForgeClientTransport implements TransportPort {

    private static final ConnectionHandle SERVER = new ConnectionHandle() { };

    private final NeoForgeServerTransport transport;

    public NeoForgeClientTransport(NeoForgeServerTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        throw new UnsupportedOperationException("客户端传输只支持无连接发送");
    }

    @Override
    public void send(byte[] data) {
        transport.sendToServer(data);
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        Objects.requireNonNull(handler, "handler 不能为空");
        transport.setClientReceiver(data -> handler.accept(SERVER, data));
    }

    @Override
    public int maxPayloadSize() {
        return transport.maxPayloadSize();
    }

    /** 客户端唯一服务端连接句柄，供断线时清理协议可靠性状态。 */
    public ConnectionHandle serverConnection() {
        return SERVER;
    }
}
