package top.wcpe.mc.mpmt.platform.bukkit.acceptance;

import java.util.Collection;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;

/**
 * Bukkit 验收控制通道（realserver harness，ADR-0014）：用 Bukkit 插件消息把测试控制协议
 * （ClientReady/RunStep/StepResult）桥接到平台无关 {@link AcceptanceClient}。
 *
 * <p>入站（客户端→服务端）在主线程派发，解码后分派 ClientReady/StepResult 给 {@link AcceptanceClient}；
 * 出站（服务端→客户端）由 {@link AcceptanceClient} 经注入回调触发，切主线程发给当前连入的单个测试客户端。
 */
public final class BukkitAcceptanceControlChannel implements PluginMessageListener {

    private static final String CHANNEL = BukkitAcceptanceControlChannelId.CHANNEL;

    /** 是否 Folia：Folia 上全局 {@code BukkitScheduler.runTask} 抛 {@code UnsupportedOperationException}，出站须改走全局区域调度器。 */
    private static final boolean FOLIA = detectFolia();

    private final Plugin plugin;
    private final AcceptanceClient client;

    public BukkitAcceptanceControlChannel(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin 不能为空");
        // 注入出站回调：AcceptanceClient 要发字节时切主线程发给测试客户端
        this.client = new AcceptanceClient(this::sendToClient);
    }

    /** 取平台无关排程协调器（供场景绑定）。 */
    public AcceptanceClient client() {
        return client;
    }

    /** 注册控制通道收发（插件启用时调用）。 */
    public void register() {
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        try {
            AcceptanceControlPacket packet = AcceptanceControlCodec.decode(message);
            if (packet instanceof ClientReadyPacket) {
                client.onClientReady((ClientReadyPacket) packet);
            } else if (packet instanceof StepResultPacket) {
                client.onStepResult((StepResultPacket) packet);
            }
            // RunStep 为 S2C，不应入站；忽略以容错
        } catch (RuntimeException e) {
            plugin.getLogger().warning("丢弃非法验收控制包：" + e.getMessage());
        }
    }

    /** 客户端断开：异常完成所有挂起步骤（由插件监听 PlayerQuitEvent 调用）。 */
    public void onClientDisconnected() {
        client.failAllPending("客户端断开");
    }

    /** 出站：切服务端线程把控制字节发给当前连入的测试客户端（单客户端模式取第一个在线玩家）。 */
    private void sendToClient(byte[] data) {
        Runnable body =
                () -> {
                    Collection<? extends Player> players = plugin.getServer().getOnlinePlayers();
                    if (!players.isEmpty()) {
                        players.iterator().next().sendPluginMessage(plugin, CHANNEL, data);
                    }
                };
        if (FOLIA) {
            // Folia 无全局主线程；出站发包落到全局区域线程（BukkitScheduler.runTask 在 Folia 抛 UnsupportedOperationException）
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> body.run());
        } else {
            plugin.getServer().getScheduler().runTask(plugin, body);
        }
    }

    /** 探测 Folia：标志类存在即 Folia（区域化调度），与 BukkitServerGameTestContext/FeatureGate 同源判据。 */
    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
