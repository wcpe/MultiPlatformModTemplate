package top.wcpe.mc.mpmt.platform.fabric.gametest;

import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricNetworkBindings;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricServerNetwork;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricVersionAdapter;

/**
 * 验收测试控制通道（仅 gametest）：经选中 L4 适配器在独立 test 通道收发控制协议字节。
 *
 * <p>不直接调用 1.20 形态的 {@code ServerPlayNetworking(ResourceLocation, FriendlyByteBuf)}，
 * 以便 1.21 typed payload 车道共用同一入口（ADR-0014 / 方案 C）。
 */
public final class FabricAcceptanceControlChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");

    private final FabricServerNetwork network;
    private final AcceptanceClient client;
    private volatile MinecraftServer server;
    private volatile ConnectionHandle clientConnection;

    public FabricAcceptanceControlChannel(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
        FabricVersionAdapter adapter = FabricNetworkBindings.selectedAdapter();
        this.network = adapter.serverNetwork(AcceptanceControlChannelId.CHANNEL);
        this.client = new AcceptanceClient(this::sendToClient);
    }

    /** 平台无关排程协调器（供场景 runClientStep）。 */
    public AcceptanceClient client() {
        return client;
    }

    /** 注册入站接收器。 */
    public void register() {
        network.registerReceiver(
                (connection, data) -> {
                    clientConnection = connection;
                    receive(data);
                });
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, srv) -> {
                    clientConnection = null;
                    client.failAllPending("客户端断开");
                });
    }

    private void receive(byte[] data) {
        try {
            AcceptanceControlPacket packet = AcceptanceControlCodec.decode(data);
            if (packet instanceof ClientReadyPacket) {
                client.onClientReady((ClientReadyPacket) packet);
            } else if (packet instanceof StepResultPacket) {
                client.onStepResult((StepResultPacket) packet);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("丢弃非法验收控制包：{}", e.getMessage());
        }
    }

    private void sendToClient(byte[] data) {
        MinecraftServer current = server;
        if (current == null) {
            return;
        }
        current.execute(
                () -> {
                    ConnectionHandle connection = clientConnection;
                    if (connection != null) {
                        network.send(connection, data);
                    }
                });
    }
}
