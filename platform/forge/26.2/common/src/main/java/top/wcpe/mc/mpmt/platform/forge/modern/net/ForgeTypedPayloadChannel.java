package top.wcpe.mc.mpmt.platform.forge.modern.net;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.payload.PayloadConnection;

/** Forge 52 通过 PayloadChannel 与 ChannelBuilder 注册类型化载荷，对上层只暴露裸字节。 */
public final class ForgeTypedPayloadChannel {

    public static final int MAX_PAYLOAD_SIZE = 1_048_576;

    private final CustomPacketPayload.Type<ForgeTypedPayload> type;
    private final Channel<CustomPacketPayload> channel;
    private volatile BiConsumer<ServerPlayer, byte[]> serverReceiver;
    private volatile Consumer<byte[]> clientReceiver;

    public ForgeTypedPayloadChannel(Identifier channelId) {
        Objects.requireNonNull(channelId, "通道标识不能为空");
        this.type = new CustomPacketPayload.Type<>(channelId);
        PayloadConnection<CustomPacketPayload> registration =
                ChannelBuilder.named(channelId)
                        .networkProtocolVersion(1)
                        .optional()
                        .payloadChannel();
        this.channel = registration.play()
                .bidirectional()
                .addMain(type, ForgeTypedPayload.codec(type), this::receive)
                .build();
    }

    public void registerServerReceiver(BiConsumer<ServerPlayer, byte[]> receiver) {
        serverReceiver = Objects.requireNonNull(receiver, "服务端收包器不能为空");
    }

    public void registerClientReceiver(Consumer<byte[]> receiver) {
        clientReceiver = Objects.requireNonNull(receiver, "客户端收包器不能为空");
    }

    public void clearClientReceiver() {
        clientReceiver = null;
    }

    public void sendToPlayer(ServerPlayer player, byte[] data) {
        Objects.requireNonNull(player, "玩家不能为空");
        channel.send(payload(data), player.connection.getConnection());
    }

    public void sendToServer(Connection connection, byte[] data) {
        channel.send(payload(data), Objects.requireNonNull(connection, "客户端连接不能为空"));
    }

    private ForgeTypedPayload payload(byte[] data) {
        return new ForgeTypedPayload(type, data);
    }

    private void receive(ForgeTypedPayload payload, CustomPayloadEvent.Context context) {
        context.setPacketHandled(true);
        if (context.isServerSide()) {
            ServerPlayer sender = context.getSender();
            BiConsumer<ServerPlayer, byte[]> receiver = serverReceiver;
            if (sender != null && receiver != null) {
                receiver.accept(sender, payload.data());
            }
            return;
        }
        Consumer<byte[]> receiver = clientReceiver;
        if (receiver != null) {
            receiver.accept(payload.data());
        }
    }
}
