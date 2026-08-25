package top.wcpe.mc.mpmt.platform.forge.modern.acceptance;

import java.util.Objects;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeTypedPayloadChannel;

/** 验收控制通道只负责编排和回报，不承载产品协议场景。 */
public final class ForgeAcceptanceControlChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");

    private final ForgeTypedPayloadChannel network;
    private final AcceptanceClient client;
    private volatile MinecraftServer server;
    private volatile ServerPlayer clientPlayer;

    public ForgeAcceptanceControlChannel(ForgeTypedPayloadChannel network) {
        this.network = Objects.requireNonNull(network, "控制网络不能为空");
        this.client = new AcceptanceClient(this::sendToClient);
    }

    public AcceptanceClient client() {
        return client;
    }

    public void bindServer(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "服务端不能为空");
    }

    public void registerServerReceiver() {
        network.registerServerReceiver(
                (player, data) -> {
                    clientPlayer = player;
                    receiveServer(data);
                });
    }

    public void registerClientReceiver(java.util.function.Consumer<byte[]> receiver) {
        network.registerClientReceiver(receiver);
    }

    public void clearClientReceiver() {
        network.clearClientReceiver();
    }

    public void sendToServer(Connection connection, AcceptanceControlPacket packet) {
        network.sendToServer(connection, AcceptanceControlCodec.encode(packet));
    }

    public void onDisconnected(ServerPlayer player) {
        if (player.equals(clientPlayer)) {
            clientPlayer = null;
            client.failAllPending("客户端断开");
        }
    }

    private void receiveServer(byte[] data) {
        try {
            AcceptanceControlPacket packet = AcceptanceControlCodec.decode(data);
            if (packet instanceof ClientReadyPacket ready) {
                client.onClientReady(ready);
            } else if (packet instanceof StepResultPacket result) {
                client.onStepResult(result);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("丢弃非法验收控制包：{}", e.getMessage());
        }
    }

    private void sendToClient(byte[] data) {
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return;
        }
        currentServer.execute(
                () -> {
                    ServerPlayer player = clientPlayer;
                    if (player != null) {
                        network.sendToPlayer(player, data);
                    }
                });
    }
}
