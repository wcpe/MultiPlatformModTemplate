package top.wcpe.mc.mpmt.platform.sponge.acceptance;

import java.util.Objects;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.network.ServerConnectionState;
import org.spongepowered.api.network.channel.ChannelBuf;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;

/**
 * Sponge 验收控制通道（realserver harness，ADR-0014）：用独立 {@link RawDataChannel} 测试通道把测试控制协议
 * （ClientReady/RunStep/StepResult）桥接到平台无关 {@link AcceptanceClient}。
 *
 * <p>入站（客户端→服务端）经 {@code play().addHandler(ServerConnectionState.Game)} 收，解码后分派
 * ClientReady/StepResult 给 {@link AcceptanceClient}；出站（服务端→客户端）由 {@link AcceptanceClient}
 * 经注入回调触发，发给当前连入的单个测试客户端。通道由插件在 {@code RegisterChannelEvent} 注册后经
 * {@link #register} 注入。
 */
public final class SpongeAcceptanceControlChannel {

    private final Logger logger;
    private final AcceptanceClient client;

    /** 控制通道；插件 {@code RegisterChannelEvent} 注册后注入。 */
    private volatile RawDataChannel channel;

    public SpongeAcceptanceControlChannel(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger 不能为空");
        // 注入出站回调：AcceptanceClient 要发字节时发给测试客户端
        this.client = new AcceptanceClient(this::sendToClient);
    }

    /** 取平台无关排程协调器（供场景绑定）。 */
    public AcceptanceClient client() {
        return client;
    }

    /** 注入已注册的控制通道并挂入站处理器（插件在 {@code RegisterChannelEvent} 调用）。 */
    public void register(RawDataChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel 不能为空");
        channel.play().addHandler(ServerConnectionState.Game.class, this::handle);
    }

    /** 客户端断开：异常完成所有挂起步骤（由插件监听断开事件调用）。 */
    public void onClientDisconnected() {
        client.failAllPending("客户端断开");
    }

    /** 入站收包：读尽缓冲解码，分派 ClientReady/StepResult；RunStep 为 S2C，不应入站，忽略以容错。 */
    private void handle(ChannelBuf buf, ServerConnectionState.Game state) {
        byte[] message = buf.readBytes(buf.available());
        try {
            AcceptanceControlPacket packet = AcceptanceControlCodec.decode(message);
            if (packet instanceof ClientReadyPacket) {
                client.onClientReady((ClientReadyPacket) packet);
            } else if (packet instanceof StepResultPacket) {
                client.onStepResult((StepResultPacket) packet);
            }
        } catch (RuntimeException e) {
            logger.warn("丢弃非法验收控制包：{}", e.getMessage());
        }
    }

    /** 出站：把控制字节发给当前连入的测试客户端（单客户端模式取第一个在线玩家）。 */
    private void sendToClient(byte[] data) {
        RawDataChannel ch = this.channel;
        if (ch == null) {
            return;
        }
        Sponge.server()
                .onlinePlayers()
                .stream()
                .findFirst()
                .ifPresent(player -> ch.play().sendTo(player, buf -> buf.writeBytes(data)));
    }
}
