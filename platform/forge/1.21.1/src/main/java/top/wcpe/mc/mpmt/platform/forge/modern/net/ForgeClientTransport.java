package top.wcpe.mc.mpmt.platform.forge.modern.net;

import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.network.Connection;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/** Forge 1.21.1 客户端裸字节传输适配。 */
public final class ForgeClientTransport implements TransportPort {

    private static final ConnectionHandle SERVER = new ConnectionHandle() {
    };

    private final ForgeTypedPayloadChannel channel;
    private final Connection connection;

    public ForgeClientTransport(ForgeTypedPayloadChannel channel, Connection connection) {
        this.channel = Objects.requireNonNull(channel, "通道不能为空");
        this.connection = Objects.requireNonNull(connection, "客户端连接不能为空");
    }

    @Override
    public void send(ConnectionHandle ignored, byte[] data) {
        throw new UnsupportedOperationException("客户端传输只支持向当前服务端发送");
    }

    @Override
    public void send(byte[] data) {
        channel.sendToServer(connection, data);
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        Objects.requireNonNull(handler, "收包器不能为空");
        channel.registerClientReceiver(data -> handler.accept(SERVER, data));
    }

    @Override
    public int maxPayloadSize() {
        return ForgeTypedPayloadChannel.MAX_PAYLOAD_SIZE;
    }

    public void clearReceiver() {
        channel.clearClientReceiver();
    }
}
