package top.wcpe.mc.mpmt.platform.sponge.capability;

import java.nio.file.Path;
import java.util.Objects;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.plugin.PluginContainer;
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
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionRegistry;

/** Sponge 平台能力装配：注册服务端能力端口，并把连接事件桥接到运行时 EventBus。 */
public final class SpongeCapabilityBootstrap {

    private SpongeCapabilityBootstrap() {
        // 工具类不实例化
    }

    /** 装配服务端能力端口与领域事件桥接。 */
    public static void register(PluginContainer plugin, Path configDir, MpmtRuntime runtime) {
        register(plugin, configDir, runtime, new SpongeConnectionRegistry());
    }

    /** 使用平台启动期共享的物理连接登记表装配能力端口。 */
    public static void register(
            PluginContainer plugin,
            Path configDir,
            MpmtRuntime runtime,
            SpongeConnectionRegistry connectionRegistry) {
        Objects.requireNonNull(plugin, "plugin 不能为空");
        Objects.requireNonNull(configDir, "configDir 不能为空");
        Objects.requireNonNull(runtime, "runtime 不能为空");
        Objects.requireNonNull(connectionRegistry, "connectionRegistry 不能为空");

        DataDirectoryPort dataDirectory = new SpongeDataDirectoryPort(configDir);
        PersistencePort persistence = new SpongePersistencePort(dataDirectory);
        MessagePort message = new SpongeMessagePort();
        ConnectionControlPort connections = new SpongeConnectionControlPort(connectionRegistry);
        PlayerPort players = new SpongePlayerPort();
        WorldPort worlds = new SpongeWorldPort();
        SchedulerPort scheduler = new SpongeSchedulerPort(plugin);

        runtime.ports().register(DataDirectoryPort.class, dataDirectory);
        runtime.ports().register(PersistencePort.class, persistence);
        runtime.ports().register(MessagePort.class, message);
        runtime.ports().register(ConnectionControlPort.class, connections);
        runtime.ports().register(PlayerPort.class, players);
        runtime.ports().register(WorldPort.class, worlds);
        runtime.ports().register(SchedulerPort.class, scheduler);
        runtime.ports().register(SpongeConnectionRegistry.class, connectionRegistry);

        EventBusPort eventBus = runtime.eventBus();
        PlatformCapabilityExample example =
                new PlatformCapabilityExample(persistence, message, scheduler, System::currentTimeMillis);
        example.register(eventBus);
        Sponge.eventManager().registerListeners(plugin, new PlayerConnectionBridge(eventBus));
    }

    /** Sponge 玩家连接事件桥接。 */
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
            org.spongepowered.api.profile.GameProfile profile = event.profile();
            eventBus.publish(
                    new PlayerLeftEvent(
                            new PlayerRef(profile.uniqueId(), profile.name().orElse(""))));
        }
    }
}
