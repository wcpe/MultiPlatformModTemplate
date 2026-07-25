package top.wcpe.mc.mpmt.platform.bukkit.capability;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.core.domain.event.EventBusPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.PlayerPort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.WorldPort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.domain.capability.PlatformCapabilityExample;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;
import top.wcpe.mc.mpmt.domain.capability.PlayerLeftEvent;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionRegistry;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapters;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;

/**
 * Bukkit 平台能力示例装配（L3，FR-26）：装配 L3 端口实现（调度 / 持久化 / 消息 / 数据目录），
 * 把同一份 L0 {@link PlatformCapabilityExample} 接到运行时自有 EventBus，
 * 并把 Bukkit 玩家进 / 退事件桥接为领域事件投递入总线（ADR-0011）。
 *
 * <p>调度经 L4 适配器选用，避免 main 直接引用 Folia 类型（1.12 车道无 Folia 字节码）。
 */
public final class BukkitCapabilityBootstrap {

    private BukkitCapabilityBootstrap() {
        // 工具类不实例化
    }

    /**
     * 装配并接线平台能力示例（无连接表时自建；适配器走 ServiceLoader）。
     *
     * @param plugin      插件实例
     * @param runtime     待注册端口的运行时
     * @param featureGate 平台能力探测
     */
    public static void register(Plugin plugin, MpmtRuntime runtime, FeatureGate featureGate) {
        register(
                plugin,
                runtime,
                featureGate,
                new BukkitConnectionRegistry(),
                BukkitVersionAdapters.load(BukkitCapabilityBootstrap.class.getClassLoader()));
    }

    /** 使用共享连接登记表与已解析的 L4 适配器装配。 */
    public static void register(
            Plugin plugin,
            MpmtRuntime runtime,
            FeatureGate featureGate,
            BukkitConnectionRegistry connectionRegistry,
            BukkitVersionAdapter adapter) {
        Objects.requireNonNull(plugin, "plugin 不能为空");
        Objects.requireNonNull(runtime, "runtime 不能为空");
        Objects.requireNonNull(featureGate, "featureGate 不能为空");
        Objects.requireNonNull(connectionRegistry, "connectionRegistry 不能为空");
        Objects.requireNonNull(adapter, "版本适配器不能为空");

        DataDirectoryPort dataDirectory = new BukkitDataDirectoryPort(plugin);
        PersistencePort persistence = new BukkitPersistencePort(dataDirectory);
        MessagePort message = new BukkitMessagePort();
        ConnectionControlPort connections =
                new BukkitConnectionControlPort(plugin, connectionRegistry);
        PlayerPort players = new BukkitPlayerPort();
        WorldPort worlds = new BukkitWorldPort();
        SchedulerPort scheduler = BukkitSchedulers.create(plugin, featureGate, adapter);

        runtime.ports().register(DataDirectoryPort.class, dataDirectory);
        runtime.ports().register(PersistencePort.class, persistence);
        runtime.ports().register(MessagePort.class, message);
        runtime.ports().register(ConnectionControlPort.class, connections);
        runtime.ports().register(PlayerPort.class, players);
        runtime.ports().register(WorldPort.class, worlds);
        runtime.ports().register(SchedulerPort.class, scheduler);
        runtime.ports().register(BukkitConnectionRegistry.class, connectionRegistry);

        EventBusPort eventBus = runtime.eventBus();
        PlatformCapabilityExample example =
                new PlatformCapabilityExample(persistence, message, scheduler, System::currentTimeMillis);
        example.register(eventBus);

        plugin.getServer()
                .getPluginManager()
                .registerEvents(new PlayerConnectionBridge(eventBus), plugin);
    }

    /** Bukkit 玩家进 / 退事件桥接：转成领域事件投递到自有 EventBus。 */
    private static final class PlayerConnectionBridge implements Listener {

        private final EventBusPort eventBus;

        PlayerConnectionBridge(EventBusPort eventBus) {
            this.eventBus = eventBus;
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            eventBus.publish(new PlayerJoinedEvent(toRef(event.getPlayer())));
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            eventBus.publish(new PlayerLeftEvent(toRef(event.getPlayer())));
        }

        private static PlayerRef toRef(Player player) {
            return new PlayerRef(player.getUniqueId(), player.getName());
        }
    }
}
