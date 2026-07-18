package top.wcpe.mc.mpmt.platform.sponge.net;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.network.ServerPlayerConnection;
import org.spongepowered.api.network.channel.ChannelBuf;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import org.spongepowered.api.network.channel.raw.play.RawPlayDataHandler;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.plugin.PluginContainer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/**
 * Sponge 服务端传输适配（L3）：用 Sponge {@link RawDataChannel} 实现 L0 {@link TransportPort} 的服务端方向。
 *
 * <p>通道 {@code mpmt:main} 与各平台一致（Fabric/Bukkit 亦此），故 Sponge 服务端可与异构客户端（Fabric）互通。
 * 只服务<b>服务端</b>方向（向连接发、收连接来包）；服务端无「无连接发送」。{@link RawDataChannel} 由插件入口在
 * {@code RegisterChannelEvent} 注册后注入本对象（通道注册只在该生命周期事件可做）。
 *
 * <p><b>线程契约</b>：Sponge 通过 {@link RawPlayDataHandler} 在服务端主线程派发入站 play 数据；
 * 上层 {@code PacketDispatcher} 线程安全，碰世界 / 领域状态前仍按归属经 SchedulerPort 切线程（ADR-0013）。
 */
public final class SpongeServerTransport implements TransportPort {

    /** vanilla 自定义负载单包上限（字节）。取保守值以与 Fabric/Bukkit 异构互通；超限由上层分片（FR-24）。 */
    private static final int MAX_PAYLOAD = 32767;

    private final RawDataChannel channel;
    private final PluginContainer plugin;

    /** 上层收包回调；启用特性时经 {@link #onReceive} 注入，入站数据早于注入则安全丢弃。 */
    private volatile BiConsumer<ConnectionHandle, byte[]> receiveHandler;

    public SpongeServerTransport(RawDataChannel channel, PluginContainer plugin) {
        this.channel = Objects.requireNonNull(channel, "channel 不能为空");
        this.plugin = Objects.requireNonNull(plugin, "plugin 不能为空");
        // RC1365 旧 API 按服务端玩家连接类型注册 play 阶段裸数据处理器
        this.channel.play().addHandler(ServerPlayerConnection.class, this::handle);
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        UUID playerId = ((SpongeConnectionHandle) connection).playerId();
        if (Sponge.server().onMainThread()) {
            sendToPlayer(playerId, data);
            return;
        }
        byte[] payload = data.clone();
        Sponge.server()
                .scheduler()
                .submit(Task.builder().execute(() -> sendToPlayer(playerId, payload)).plugin(plugin).build());
    }

    @Override
    public void send(byte[] data) {
        throw new UnsupportedOperationException("服务端传输不支持无连接发送");
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        this.receiveHandler = Objects.requireNonNull(handler, "handler 不能为空");
    }

    @Override
    public int maxPayloadSize() {
        return MAX_PAYLOAD;
    }

    /** 按 UUID 查询当前在线玩家并发送；玩家已离线则静默丢弃。 */
    private void sendToPlayer(UUID playerId, byte[] data) {
        Sponge.server()
                .player(playerId)
                .ifPresent(player -> channel.play().sendTo(player, buf -> buf.writeBytes(data)));
    }

    /** Sponge 入站 play 数据回调（主线程）：已注入上层回调时，读尽缓冲裸字节并附连接句柄转交。 */
    private void handle(ChannelBuf buf, ServerPlayerConnection connection) {
        BiConsumer<ConnectionHandle, byte[]> handler = this.receiveHandler;
        if (handler == null) {
            return;
        }
        byte[] data = buf.readBytes(buf.available());
        UUID playerId = connection.player().uniqueId();
        handler.accept(new SpongeConnectionHandle(playerId), data);
    }
}
