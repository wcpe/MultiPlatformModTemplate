package top.wcpe.mc.mpmt.platform.bukkit.capability;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.core.domain.event.EventBusPort;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.domain.capability.PlatformCapabilityExample;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;
import top.wcpe.mc.mpmt.domain.capability.PlayerLeftEvent;

/**
 * Bukkit 平台能力示例装配（L3，FR-26）：装配 L3 端口实现（调度 / 持久化 / 消息 / 数据目录），
 * 把同一份 L0 {@link PlatformCapabilityExample} 接到运行时自有 EventBus，
 * 并把 Bukkit 玩家进 / 退事件桥接为领域事件投递入总线（ADR-0011）。
 *
 * <p>这正是脚手架"一份 L0 逻辑经端口在各平台一致运行"的 Bukkit 落地：与 Fabric 镜像同一份 L0。
 */
public final class BukkitCapabilityBootstrap {

    private BukkitCapabilityBootstrap() {
        // 工具类不实例化
    }

    /**
     * 装配并接线平台能力示例。
     *
     * @param plugin   插件实例（提供数据目录 / 调度 / 在线玩家 / 监听器注册）
     * @param eventBus 运行时自有 EventBus（领域事件投递与订阅同一条总线）
     */
    public static void register(Plugin plugin, EventBusPort eventBus) {
        Objects.requireNonNull(plugin, "plugin 不能为空");
        Objects.requireNonNull(eventBus, "eventBus 不能为空");

        DataDirectoryPort dataDirectory = new BukkitDataDirectoryPort(plugin);
        PersistencePort persistence = new BukkitPersistencePort(dataDirectory);
        MessagePort message = new BukkitMessagePort();
        SchedulerPort scheduler = new BukkitSchedulerPort(plugin);

        PlatformCapabilityExample example =
                new PlatformCapabilityExample(persistence, message, scheduler, System::currentTimeMillis);
        example.register(eventBus);

        // 平台事件 → 领域事件 → 自有 EventBus（ADR-0011）
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new PlayerConnectionBridge(eventBus), plugin);
    }

    /** Bukkit 玩家进 / 退事件桥接：转成领域事件投递到自有 EventBus，不在 L3 写执行逻辑（ADR-0009）。 */
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
