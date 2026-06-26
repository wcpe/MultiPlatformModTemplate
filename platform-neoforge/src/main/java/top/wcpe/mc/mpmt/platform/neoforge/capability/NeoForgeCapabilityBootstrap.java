package top.wcpe.mc.mpmt.platform.neoforge.capability;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
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
 * NeoForge 平台能力示例装配（L3，FR-26）：服务端启动后装配 L3 端口实现（调度 / 持久化 / 消息 / 数据目录），
 * 把同一份 L0 {@link PlatformCapabilityExample} 接到自有 EventBus，并把 NeoForge 玩家登录 / 登出事件桥接为领域事件。
 *
 * <p>端口需 {@link MinecraftServer}（找玩家 / 切主线程），故在 {@link ServerStartedEvent} 装配（不在 mod 构造期）。
 * 这正是脚手架"一份 L0 逻辑经端口在各平台一致运行"的 NeoForge 落地，与 Fabric / Bukkit 镜像同一份 L0。
 *
 * <p>NeoForge 20.2 经游戏事件总线 {@link NeoForge#EVENT_BUS} 订阅（{@code @SubscribeEvent} 注解 + {@code register}），
 * 玩家进退事件为 {@link PlayerEvent.PlayerLoggedInEvent} / {@link PlayerEvent.PlayerLoggedOutEvent}。
 */
public final class NeoForgeCapabilityBootstrap {

    /** 自有 EventBus：服务端启动后接入 L0 示例与玩家事件桥；启动前为空。 */
    private volatile EventBusPort eventBus;

    private NeoForgeCapabilityBootstrap() {
        // 仅经 register 装配
    }

    /** 由 mod 入口调用：在游戏事件总线上挂接服务端启动与玩家进退事件。 */
    public static void register() {
        NeoForge.EVENT_BUS.register(new NeoForgeCapabilityBootstrap());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        DataDirectoryPort dataDirectory = new NeoForgeDataDirectoryPort();
        PersistencePort persistence = new NeoForgePersistencePort(dataDirectory);
        MessagePort message = new NeoForgeMessagePort(server);
        SchedulerPort scheduler = new NeoForgeSchedulerPort(server);

        EventBusPort bus = new SimpleEventBus();
        PlatformCapabilityExample example =
                new PlatformCapabilityExample(persistence, message, scheduler, System::currentTimeMillis);
        example.register(bus);
        this.eventBus = bus;
    }

    /** 平台事件 → 领域事件 → 自有 EventBus（ADR-0011）；服务端启动前（总线未就绪）静默忽略。 */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        EventBusPort bus = eventBus;
        if (bus != null) {
            bus.publish(new PlayerJoinedEvent(toRef(event.getEntity())));
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        EventBusPort bus = eventBus;
        if (bus != null) {
            bus.publish(new PlayerLeftEvent(toRef(event.getEntity())));
        }
    }

    private static PlayerRef toRef(Player player) {
        return new PlayerRef(player.getUUID(), player.getName().getString());
    }
}
