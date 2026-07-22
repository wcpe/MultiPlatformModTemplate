package top.wcpe.mc.mpmt.platform.forge.capability;

import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
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

/** Forge 平台能力装配：注册共享 capability 所需端口，并把玩家事件桥接到运行时 EventBus。 */
public final class ForgeCapabilityBootstrap {

    private ForgeCapabilityBootstrap() {
        // 工具类不实例化
    }

    /** 装配服务端能力端口与领域事件桥接。 */
    public static void register(MinecraftServer server, MpmtRuntime runtime) {
        Objects.requireNonNull(server, "server 不能为空");
        Objects.requireNonNull(runtime, "runtime 不能为空");

        DataDirectoryPort dataDirectory = new ForgeDataDirectoryPort();
        PersistencePort persistence = new ForgePersistencePort(dataDirectory);
        MessagePort message = new ForgeMessagePort(server);
        ConnectionControlPort connections = new ForgeConnectionControlPort(server);
        PlayerPort players = new ForgePlayerPort(server);
        WorldPort worlds = new ForgeWorldPort(server);
        SchedulerPort scheduler = new ForgeSchedulerPort(server);

        runtime.ports().register(DataDirectoryPort.class, dataDirectory);
        runtime.ports().register(PersistencePort.class, persistence);
        runtime.ports().register(MessagePort.class, message);
        runtime.ports().register(ConnectionControlPort.class, connections);
        runtime.ports().register(PlayerPort.class, players);
        runtime.ports().register(WorldPort.class, worlds);
        runtime.ports().register(SchedulerPort.class, scheduler);

        EventBusPort eventBus = runtime.eventBus();
        PlatformCapabilityExample example =
                new PlatformCapabilityExample(persistence, message, scheduler, System::currentTimeMillis);
        example.register(eventBus);
        MinecraftForge.EVENT_BUS.register(new PlayerConnectionBridge(eventBus));
    }

    /** Forge 玩家登录与登出事件桥接。 */
    public static final class PlayerConnectionBridge {
        private final EventBusPort eventBus;

        PlayerConnectionBridge(EventBusPort eventBus) {
            this.eventBus = eventBus;
        }

        @SubscribeEvent
        public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            eventBus.publish(new PlayerJoinedEvent(toRef(event.getEntity())));
        }

        @SubscribeEvent
        public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            eventBus.publish(new PlayerLeftEvent(toRef(event.getEntity())));
        }
    }

    private static PlayerRef toRef(Player player) {
        return new PlayerRef(player.getUUID(), player.getName().getString());
    }
}
