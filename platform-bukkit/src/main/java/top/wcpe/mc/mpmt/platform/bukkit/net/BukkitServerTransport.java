package top.wcpe.mc.mpmt.platform.bukkit.net;

import java.util.Objects;
import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/**
 * Bukkit 服务端传输适配（L3）：用 Bukkit 插件消息（{@link Messenger}）实现 L0 {@link TransportPort} 的服务端方向。
 *
 * <p>通道 {@code mpmt:main} 与各平台一致（Fabric 亦此），故 Bukkit 服务端可与异构客户端（Fabric/Forge）互通。
 * 只服务<b>服务端</b>方向（向连接发、收连接来包）；服务端无「无连接发送」。1.20.1 单锚点下插件消息 API 无版本差异，
 * 故不引入 vX_Y 子层（用到才建，scope-discipline）；Paper/Folia 的线程差异由上层经 SchedulerPort 适配（ADR-0013）。
 *
 * <p><b>线程契约</b>：Bukkit 在服务端主线程派发入站插件消息，{@link #onPluginMessageReceived} 在主线程触发；
 * 上层 {@code PacketDispatcher} 线程安全，碰世界 / 领域状态前仍按归属经 SchedulerPort 切线程。
 */
public final class BukkitServerTransport implements TransportPort, PluginMessageListener {

    /** 产品跨端通道（namespace:path）。须与各平台一致（Fabric: {@code mpmt:main}）以支持异构互通。 */
    private static final String CHANNEL = "mpmt:main";

    private final Plugin plugin;

    /** 上层收包回调；启用特性时经 {@link #onReceive} 注入，入站消息早于注入则安全丢弃。 */
    private volatile BiConsumer<ConnectionHandle, byte[]> receiveHandler;

    public BukkitServerTransport(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin 不能为空");
        Messenger messenger = plugin.getServer().getMessenger();
        // 出站 + 入站通道一次性注册；入站监听器即本对象
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        Player player = ((BukkitConnectionHandle) connection).player();
        player.sendPluginMessage(plugin, CHANNEL, data);
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
        return Messenger.MAX_MESSAGE_SIZE;
    }

    /** Bukkit 入站插件消息回调（主线程）：仅产品通道、且已注入上层回调时转交裸字节。 */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        BiConsumer<ConnectionHandle, byte[]> handler = this.receiveHandler;
        if (handler == null) {
            return;
        }
        handler.accept(new BukkitConnectionHandle(player), message);
    }
}
