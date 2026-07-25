package top.wcpe.mc.mpmt.platform.sponge.acceptance;

import java.util.Objects;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.network.ServerPlayerConnection;
import org.spongepowered.api.network.channel.ChannelBuf;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.plugin.PluginContainer;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;

/**
 * Sponge 实机验收控制通道（ADR-0014）：使用独立 {@link RawDataChannel} 把验收控制协议桥接到平台无关的
 * {@link AcceptanceClient}。
 *
 * <p>客户端到服务端的数据由 {@link ServerPlayerConnection} 连接处理器接收，解码后分派客户端就绪包和步骤结果包；
 * 服务端到客户端的数据由 {@link AcceptanceClient} 的发送回调触发，并在服务端主线程发送给当前接入的单个测试客户端。
 * 控制通道由插件注册后通过 {@link #register} 注入。
 */
public final class SpongeAcceptanceControlChannel {

    private final Logger logger;
    private final PluginContainer plugin;
    private final AcceptanceClient client;

    /** 控制通道；插件处理通道注册事件后注入。 */
    private volatile RawDataChannel channel;

    public SpongeAcceptanceControlChannel(Logger logger, PluginContainer plugin) {
        this.logger = Objects.requireNonNull(logger, "logger 不能为空");
        this.plugin = Objects.requireNonNull(plugin, "plugin 不能为空");
        // 注入出站回调：平台无关客户端需要发送字节时转发给测试客户端
        this.client = new AcceptanceClient(this::sendToClient);
    }

    /** 取平台无关排程协调器（供场景绑定）。 */
    public AcceptanceClient client() {
        return client;
    }

    /** 注入已注册的控制通道，并为服务端玩家连接挂载入站处理器。 */
    public void register(RawDataChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel 不能为空");
        channel.play().addHandler(ServerPlayerConnection.class, this::handle);
    }

    /** 客户端断开：异常完成所有挂起步骤（由插件监听断开事件调用）。 */
    public void onClientDisconnected() {
        client.failAllPending("客户端断开");
    }

    /** 入站收包：读尽缓冲区并解码，分派客户端就绪包和步骤结果包；服务端步骤包入站时忽略。 */
    private void handle(ChannelBuf buf, ServerPlayerConnection connection) {
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

    /** 出站：在服务端主线程把控制字节发送给当前接入的测试客户端。 */
    private void sendToClient(byte[] data) {
        if (Sponge.server().onMainThread()) {
            sendOnMainThread(data);
            return;
        }
        Sponge.server()
                .scheduler()
                .submit(Task.builder().execute(() -> sendOnMainThread(data)).plugin(plugin).build());
    }

    /** 查询单客户端模式下的在线玩家，并通过已注册通道发送控制字节。 */
    private void sendOnMainThread(byte[] data) {
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
