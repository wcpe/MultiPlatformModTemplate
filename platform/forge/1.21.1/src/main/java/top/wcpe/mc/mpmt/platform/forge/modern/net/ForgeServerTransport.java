package top.wcpe.mc.mpmt.platform.forge.modern.net;

import java.util.Objects;
import java.util.function.BiConsumer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/** Forge 1.21.1 服务端裸字节传输适配。 */
public final class ForgeServerTransport implements TransportPort {

    private final ForgeTypedPayloadChannel channel;

    public ForgeServerTransport(ForgeTypedPayloadChannel channel) {
        this.channel = Objects.requireNonNull(channel, "通道不能为空");
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        channel.sendToPlayer(((ForgeConnectionHandle) connection).player(), data);
    }

    @Override
    public void send(byte[] data) {
        throw new UnsupportedOperationException("服务端传输不支持无连接发送");
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        Objects.requireNonNull(handler, "收包器不能为空");
        channel.registerServerReceiver(
                (player, data) -> handler.accept(new ForgeConnectionHandle(player), data));
    }

    @Override
    public int maxPayloadSize() {
        return ForgeTypedPayloadChannel.MAX_PAYLOAD_SIZE;
    }
}
