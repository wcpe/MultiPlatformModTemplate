package top.wcpe.mc.mpmt.platform.forge.net;

import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/** Forge 1.12.2 客户端裸字节通道适配器。 */
public final class ForgeClientTransport implements ForgeClientTransportPort {

    private static final int MAX_PAYLOAD_SIZE = 32767;

    private final String channelName;
    private final FMLEventChannel channel;
    private volatile BiConsumer<ConnectionHandle, byte[]> receiver;

    public ForgeClientTransport(String channelName) {
        this.channelName = Objects.requireNonNull(channelName, "channelName 不能为空");
        this.channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(channelName);
        this.channel.register(this);
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        throw new UnsupportedOperationException("client-only 传输不支持向指定客户端发送");
    }

    @Override
    public void send(byte[] data) {
        channel.sendToServer(ForgePayloadCodec.outgoing(channelName, data));
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        receiver = Objects.requireNonNull(handler, "handler 不能为空");
    }

    @Override
    public int maxPayloadSize() {
        return MAX_PAYLOAD_SIZE;
    }

    @Override
    public void clearReceiver() {
        receiver = null;
    }

    @SubscribeEvent
    public void onClientPayload(FMLNetworkEvent.ClientCustomPacketEvent event) {
        BiConsumer<ConnectionHandle, byte[]> current = receiver;
        if (current != null) {
            current.accept(null, ForgePayloadCodec.incoming(event.getPacket()));
        }
    }
}
