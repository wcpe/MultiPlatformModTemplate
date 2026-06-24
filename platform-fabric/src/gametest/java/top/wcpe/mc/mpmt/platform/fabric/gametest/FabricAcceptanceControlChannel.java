package top.wcpe.mc.mpmt.platform.fabric.gametest;

import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;

/**
 * 验收测试控制通道的 Fabric 实现（仅 gametest 接入层）：经 {@code ServerPlayNetworking} 的<b>独立 test 通道</b>
 * {@code mpmt-test:acceptance} 收发控制协议字节，桥接平台无关 {@link AcceptanceClient}。
 *
 * <p>与产品通道 {@code mpmt:main} 分离，且仅存在于 gametest 源集、不入产品 jar（ADR-0014：测试控制协议不污染产品）。
 * 入站（ClientReady/StepResult）在网络线程解码后分派给协调器；出站（RunStep）切主线程发给当前连入的测试客户端。
 */
public final class FabricAcceptanceControlChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");

    private final MinecraftServer server;
    private final AcceptanceClient client;

    public FabricAcceptanceControlChannel(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
        this.client = new AcceptanceClient(this::sendToClient);
    }

    /** 平台无关排程协调器（供场景 runClientStep）。 */
    public AcceptanceClient client() {
        return client;
    }

    /** 注册入站接收器。 */
    public void register() {
        ServerPlayNetworking.registerGlobalReceiver(
                AcceptanceControlChannelId.CHANNEL,
                (srv, player, handler, buf, responseSender) -> {
                    byte[] data = readAll(buf);
                    try {
                        AcceptanceControlPacket packet = AcceptanceControlCodec.decode(data);
                        if (packet instanceof ClientReadyPacket) {
                            client.onClientReady((ClientReadyPacket) packet);
                        } else if (packet instanceof StepResultPacket) {
                            client.onStepResult((StepResultPacket) packet);
                        }
                        // RunStep 为 S2C，不应从客户端入站；忽略
                    } catch (RuntimeException e) {
                        // 非法 / 截断 / 未知控制包：丢弃并记录，不打断接收器（类比产品 PacketDispatcher 容错）
                        LOGGER.warn("丢弃非法验收控制包：{}", e.getMessage());
                    }
                });
        // 客户端断开：即时唤醒所有挂起的 runClientStep（否则只能等满超时）
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, srv) -> client.failAllPending("客户端断开"));
    }

    /** 出站：切主线程把字节发给当前连入的测试客户端（单客户端）。 */
    private void sendToClient(byte[] data) {
        server.execute(
                () -> {
                    List<ServerPlayer> players = server.getPlayerList().getPlayers();
                    if (!players.isEmpty()) {
                        FriendlyByteBuf buf = PacketByteBufs.create();
                        buf.writeBytes(data);
                        ServerPlayNetworking.send(players.get(0), AcceptanceControlChannelId.CHANNEL, buf);
                    }
                });
    }

    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }
}
