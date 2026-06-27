package top.wcpe.mc.mpmt.platform.sponge.capability;

import java.nio.file.Path;
import java.util.Objects;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.plugin.PluginContainer;
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
 * Sponge 平台能力示例装配（L3，FR-26）：装配 L3 端口实现（调度 / 持久化 / 消息 / 数据目录），
 * 把同一份 L0 {@link PlatformCapabilityExample} 接到运行时自有 EventBus，
 * 并把 Sponge 玩家进 / 退连接事件桥接为领域事件投递入总线（ADR-0011）。
 *
 * <p>这正是脚手架"一份 L0 逻辑经端口在各平台一致运行"的 Sponge 落地：与 Fabric / Bukkit / NeoForge 镜像同一份 L0。
 */
public final class SpongeCapabilityBootstrap {

    private SpongeCapabilityBootstrap() {
        // 工具类不实例化
    }

    /**
     * 装配并接线平台能力示例。
     *
     * @param plugin    插件容器（调度 / 玩家事件监听器注册）
     * @param configDir 插件配置目录（数据基目录，经 {@code @ConfigDir} 注入）
     * @param eventBus  运行时自有 EventBus（领域事件投递与订阅同一条总线）
     */
    public static void register(PluginContainer plugin, Path configDir, EventBusPort eventBus) {
        Objects.requireNonNull(plugin, "plugin 不能为空");
        Objects.requireNonNull(configDir, "configDir 不能为空");
        Objects.requireNonNull(eventBus, "eventBus 不能为空");

        DataDirectoryPort dataDirectory = new SpongeDataDirectoryPort(configDir);
        PersistencePort persistence = new SpongePersistencePort(dataDirectory);
        MessagePort message = new SpongeMessagePort();
        SchedulerPort scheduler = new SpongeSchedulerPort(plugin);

        PlatformCapabilityExample example =
                new PlatformCapabilityExample(persistence, message, scheduler, System::currentTimeMillis);
        example.register(eventBus);

        // 平台事件 → 领域事件 → 自有 EventBus（ADR-0011）；不在 L3 写执行逻辑（ADR-0009）
        Sponge.eventManager().registerListeners(plugin, new PlayerConnectionBridge(eventBus));
    }

    /** Sponge 玩家进 / 退连接事件桥接：转成领域事件投递到自有 EventBus。 */
    public static final class PlayerConnectionBridge {

        private final EventBusPort eventBus;

        PlayerConnectionBridge(EventBusPort eventBus) {
            this.eventBus = eventBus;
        }

        @Listener
        public void onJoin(ServerSideConnectionEvent.Join event) {
            ServerPlayer player = event.player();
            eventBus.publish(new PlayerJoinedEvent(new PlayerRef(player.uniqueId(), player.name())));
        }

        @Listener
        public void onDisconnect(ServerSideConnectionEvent.Disconnect event) {
            // 已完成登录的玩家断开才有 profile；早退（profile 缺失）无对应领域玩家，忽略
            event.profile()
                    .ifPresent(
                            profile ->
                                    eventBus.publish(
                                            new PlayerLeftEvent(
                                                    new PlayerRef(
                                                            profile.uniqueId(),
                                                            profile.name().orElse("")))));
        }
    }
}
