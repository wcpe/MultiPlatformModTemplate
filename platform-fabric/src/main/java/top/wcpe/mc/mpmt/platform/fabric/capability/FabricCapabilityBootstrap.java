package top.wcpe.mc.mpmt.platform.fabric.capability;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.event.EventBusPort;
import top.wcpe.mc.mpmt.core.domain.event.SimpleEventBus;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.domain.capability.PlatformCapabilityExample;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;
import top.wcpe.mc.mpmt.domain.capability.PlayerLeftEvent;

/**
 * Fabric 平台能力示例装配（L3，FR-26）：服务端启动后装配 L3 端口实现（调度 / 持久化 / 消息 / 数据目录），
 * 把同一份 L0 {@link PlatformCapabilityExample} 接到自有 EventBus，并把 Fabric 玩家连接事件桥接为领域事件。
 *
 * <p>端口需 {@link MinecraftServer}（找玩家 / 切主线程），故在 {@code SERVER_STARTED} 装配（不在 mod init）。
 * 这正是脚手架"一份 L0 逻辑经端口在各平台一致运行"的 Fabric 落地。
 */
public final class FabricCapabilityBootstrap {

    private FabricCapabilityBootstrap() {
        // 工具类不实例化
    }

    /** 由 mod 入口调用：挂接服务端启动事件。 */
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(FabricCapabilityBootstrap::onServerStarted);
    }

    private static void onServerStarted(MinecraftServer server) {
        DataDirectoryPort dataDirectory = new FabricDataDirectoryPort();
        PersistencePort persistence = new FabricPersistencePort(dataDirectory);
        MessagePort message = new FabricMessagePort(server);
        SchedulerPort scheduler = new FabricSchedulerPort(server);

        EventBusPort eventBus = new SimpleEventBus();
        PlatformCapabilityExample example =
                new PlatformCapabilityExample(persistence, message, scheduler, System::currentTimeMillis);
        example.register(eventBus);

        // 平台事件 → 领域事件 → 自有 EventBus（ADR-0011）
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, srv) -> eventBus.publish(new PlayerJoinedEvent(toRef(handler.player))));
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, srv) -> eventBus.publish(new PlayerLeftEvent(toRef(handler.player))));
    }

    private static PlayerRef toRef(ServerPlayer player) {
        return new PlayerRef(player.getUUID(), player.getName().getString());
    }
}
