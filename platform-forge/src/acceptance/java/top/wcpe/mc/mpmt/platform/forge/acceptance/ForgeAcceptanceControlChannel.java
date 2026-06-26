package top.wcpe.mc.mpmt.platform.forge.acceptance;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;

/**
 * Forge 验收控制通道（realserver harness，ADR-0014）：用 Forge {@link EventNetworkChannel} 的<b>裸字节</b>
 * 把测试控制协议（ClientReady/RunStep/StepResult）桥接到平台无关 {@link AcceptanceClient}。
 *
 * <p><b>必须用 EventNetworkChannel 而非 SimpleChannel</b>：SimpleChannel 会在 payload 前加消息索引帧字节，
 * 与 Fabric 验收伴侣的裸字节控制协议不兼容；EventNetworkChannel 收到 / 发出的均为裸字节，故可与 Fabric
 * 客户端伴侣按同一控制协议互通（异构互通，FR-11②）。收发写法对齐产品 {@code ForgeServerTransport}。
 *
 * <p>入站（客户端→服务端）在网络线程触发，解码后分派 ClientReady/StepResult 给 {@link AcceptanceClient}；
 * 出站（服务端→客户端）由 {@link AcceptanceClient} 经注入回调触发，切主线程发给当前连入的单个测试客户端。
 */
public final class ForgeAcceptanceControlChannel {

    /** 服务端在启动后绑定（出站发送定位玩家用）；通道注册须早于此，故不能在构造期要求 server。 */
    private volatile MinecraftServer server;

    private final AcceptanceClient client;
    private final EventNetworkChannel channel;

    public ForgeAcceptanceControlChannel() {
        // 注入出站回调：AcceptanceClient 要发字节时切主线程发给测试客户端
        this.client = new AcceptanceClient(this::sendToClient);
        // **通道注册必须在 mod 构造期（Forge 注册阶段）**——注册阶段结束后 NetworkRegistry 会锁定，
        // ServerStarted 再注册会抛「Registration of impl channels is locked」。与产品 ForgeServerTransport 同。
        // 接受任意对端版本谓词一律放行（含 Fabric 客户端伴侣的缺省版本），支持异构互通。
        this.channel =
                NetworkRegistry.newEventChannel(
                        ForgeAcceptanceControlChannelId.CHANNEL, () -> "1", v -> true, v -> true);
        this.channel.addListener(this::onServerPayload);
    }

    /** 服务端启动后绑定（供出站发送定位在线玩家）。 */
    public void bindServer(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    /** 取平台无关排程协调器（供场景绑定）。 */
    public AcceptanceClient client() {
        return client;
    }

    /** 服务端收到客户端的裸控制 payload：读出字节解码后分派给协调器。 */
    private void onServerPayload(NetworkEvent.ServerCustomPayloadEvent event) {
        NetworkEvent.Context ctx = event.getSource().get();
        byte[] data = readAll(event.getPayload());
        try {
            AcceptanceControlPacket packet = AcceptanceControlCodec.decode(data);
            if (packet instanceof ClientReadyPacket) {
                client.onClientReady((ClientReadyPacket) packet);
            } else if (packet instanceof StepResultPacket) {
                client.onStepResult((StepResultPacket) packet);
            }
            // RunStep 为 S2C，不应入站；忽略以容错
        } catch (RuntimeException e) {
            // 非法 / 截断 / 未知控制包：丢弃不打断接收器（类比产品收发的容错）
            org.slf4j.LoggerFactory.getLogger("mpmt-acceptance")
                    .warn("丢弃非法验收控制包：{}", e.getMessage());
        }
        ctx.setPacketHandled(true);
    }

    /** 客户端断开：异常完成所有挂起步骤（由驱动监听 PlayerLoggedOut 调用）。 */
    public void onClientDisconnected() {
        client.failAllPending("客户端断开");
    }

    /** 出站：切主线程把控制字节裸发给当前连入的测试客户端（单客户端模式取第一个在线玩家）。 */
    private void sendToClient(byte[] data) {
        MinecraftServer current = server;
        if (current == null) {
            // 服务端尚未绑定（正常流程不会发生：发送都在 ServerStarted 绑定之后）
            return;
        }
        current.execute(
                () -> {
                    List<ServerPlayer> players = current.getPlayerList().getPlayers();
                    if (!players.isEmpty()) {
                        FriendlyByteBuf buf =
                                new FriendlyByteBuf(Unpooled.buffer(data.length == 0 ? 1 : data.length));
                        buf.writeBytes(data);
                        // 裸 CustomPayload（无 SimpleChannel 帧），与 Fabric 裸字节控制协议互通
                        players.get(0)
                                .connection
                                .send(
                                        new ClientboundCustomPayloadPacket(
                                                ForgeAcceptanceControlChannelId.CHANNEL, buf));
                    }
                });
    }

    /** 在网络线程立即读出全部可读字节（buf 随后释放）。 */
    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }
}
