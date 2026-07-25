package top.wcpe.mc.mpmt.platform.neoforge.version.v1_20_2;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.NetworkRegistry;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.simple.SimpleChannel;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeConnectionHandle;
import top.wcpe.mc.mpmt.platform.neoforge.version.NeoForgeServerNetwork;

/**
 * NeoForge 1.20.2 服务端网络适配：复用现有 SimpleChannel 栈，不重复注册。
 *
 * <p>只抽取通道注册、收发、连接转换与单包上限。
 */
public final class V1_20_2ServerNetwork implements NeoForgeServerNetwork {

    private static final int MAX_PAYLOAD = 1048576;
    private static final String PROTOCOL_VERSION = "1";
    private static final int RAW_MESSAGE_ID = 0;

    private final SimpleChannel channel;
    private volatile BiConsumer<ServerPlayer, byte[]> receiveHandler;
    private volatile Consumer<byte[]> clientReceiver;

    public V1_20_2ServerNetwork(String namespace, String path) {
        this.channel =
                NetworkRegistry.newSimpleChannel(
                        new ResourceLocation(namespace, path),
                        () -> PROTOCOL_VERSION,
                        version -> true,
                        version -> true);
        registerRawMessage();
    }

    private void registerRawMessage() {
        channel.messageBuilder(RawMessage.class, RAW_MESSAGE_ID)
                .encoder((msg, buf) -> buf.writeByteArray(msg.data))
                .decoder(buf -> new RawMessage(buf.readByteArray()))
                .consumerMainThread(
                        (msg, ctx) -> {
                            ServerPlayer sender = ctx.getSender();
                            if (sender != null) {
                                BiConsumer<ServerPlayer, byte[]> handler = receiveHandler;
                                if (handler != null) {
                                    handler.accept(sender, msg.data);
                                }
                            } else {
                                Consumer<byte[]> client = clientReceiver;
                                if (client != null) {
                                    client.accept(msg.data);
                                }
                            }
                            ctx.setPacketHandled(true);
                        })
                .add();
    }

    @Override
    public void registerReceiver(BiConsumer<ServerPlayer, byte[]> handler) {
        this.receiveHandler = Objects.requireNonNull(handler, "handler 不能为空");
    }

    @Override
    public void setClientReceiver(Consumer<byte[]> receiver) {
        this.clientReceiver = Objects.requireNonNull(receiver, "receiver 不能为空");
    }

    @Override
    public void sendToServer(byte[] data) {
        channel.sendToServer(new RawMessage(data));
    }

    @Override
    public void send(ServerPlayer player, byte[] data) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), new RawMessage(data));
    }

    @Override
    public ConnectionHandle connectionOf(ServerPlayer player) {
        return new NeoForgeConnectionHandle(player);
    }

    @Override
    public int maxPayloadSize() {
        return MAX_PAYLOAD;
    }

    private static final class RawMessage {
        private final byte[] data;

        RawMessage(byte[] data) {
            this.data = data;
        }
    }
}
