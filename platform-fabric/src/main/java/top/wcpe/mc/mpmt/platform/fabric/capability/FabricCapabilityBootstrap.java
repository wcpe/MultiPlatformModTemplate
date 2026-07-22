package top.wcpe.mc.mpmt.platform.fabric.capability;

import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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

/**
 * Fabric 平台能力装配：在服务端运行时启用前注册能力端口，并把玩家事件桥接到唯一的运行时 EventBus。
 */
public final class FabricCapabilityBootstrap {

    private FabricCapabilityBootstrap() {
        // 工具类不实例化
    }

    /** 装配服务端能力端口与领域事件桥接。 */
    public static void register(MinecraftServer server, MpmtRuntime runtime) {
        Objects.requireNonNull(server, "server 不能为空");
        Objects.requireNonNull(runtime, "runtime 不能为空");

        DataDirectoryPort dataDirectory = new FabricDataDirectoryPort();
        PersistencePort persistence = new FabricPersistencePort(dataDirectory);
        MessagePort message = new FabricMessagePort(server);
        ConnectionControlPort connections = new FabricConnectionControlPort(server);
        PlayerPort players = new FabricPlayerPort(server);
        WorldPort worlds = new FabricWorldPort(server);
        SchedulerPort scheduler = new FabricSchedulerPort(server);

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

        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, srv) -> eventBus.publish(new PlayerJoinedEvent(toRef(handler.player))));
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, srv) -> eventBus.publish(new PlayerLeftEvent(toRef(handler.player))));
    }

    private static PlayerRef toRef(ServerPlayer player) {
        return new PlayerRef(player.getUUID(), player.getName().getString());
    }
}
