package top.wcpe.mc.mpmt.platform.forge.acceptance;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeRawPayloadRouter;

/**
 * Forge 验收控制通道（realserver harness，ADR-0014 / ADR-0018）：用<b>裸 vanilla {@code CustomPayload}</b> 把测试控制
 * 协议（ClientReady/RunStep/StepResult）桥接到平台无关 {@link AcceptanceClient}。
 *
 * <p><b>裸字节 + Mixin 收包</b>（ADR-0018）：服务端收包经 {@link ForgeRawPayloadRouter} 注册——Mixin 在原版
 * {@code handleCustomPayload} 拦到本通道（{@code mpmt-test:acceptance}）裸包后切主线程分发到此；出站经原版
 * {@link ClientboundCustomPayloadPacket} 裸发。与产品 {@code ForgeServerTransport} 同机制，且字节与 Fabric/Bukkit
 * 验收伴侣一致（异构互通 FR-11②）。
 *
 * <p>入站（客户端→服务端）分派 ClientReady/StepResult 给 {@link AcceptanceClient}；出站（服务端→客户端）由
 * {@link AcceptanceClient} 经注入回调触发，切主线程发给当前连入的单个测试客户端。
 */
public final class ForgeAcceptanceControlChannel {

    /** 验收控制通道（{@code mpmt-test:acceptance}）。 */
    private final ResourceLocation channelId = ForgeAcceptanceControlChannelId.CHANNEL;

    /** 服务端在启动后绑定（出站发送定位玩家用）。 */
    private volatile MinecraftServer server;

    private final AcceptanceClient client;

    public ForgeAcceptanceControlChannel() {
        // 注入出站回调：AcceptanceClient 要发字节时切主线程发给测试客户端
        this.client = new AcceptanceClient(this::sendToClient);
        // 向裸 payload 路由注册服务端收包：Mixin 拦到本通道裸包→主线程分发到此（ADR-0018）
        ForgeRawPayloadRouter.registerServer(channelId, (player, data) -> onServerControl(data));
    }

    /** 服务端启动后绑定（供出站发送定位在线玩家）。 */
    public void bindServer(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    /** 取平台无关排程协调器（供场景绑定）。 */
    public AcceptanceClient client() {
        return client;
    }

    /** 验收控制通道资源位置（供客户端伴侣注册同一通道收包 + 裸发上报）。 */
    public ResourceLocation channelId() {
        return channelId;
    }

    /** 服务端收到客户端控制字节（已在主线程，由路由切入）：解码后分派给协调器。 */
    private void onServerControl(byte[] data) {
        org.slf4j.LoggerFactory.getLogger("mpmt-acceptance")
                .info("收到客户端控制 payload，{} 字节", data.length);
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
                        players.get(0)
                                .connection
                                .send(new ClientboundCustomPayloadPacket(channelId, buf));
                    }
                });
    }
}
