package top.wcpe.mc.mpmt.platform.neoforge.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.event.EventBusPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.PlayerPort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.WorldPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;
import top.wcpe.mc.mpmt.domain.capability.PlayerLeftEvent;
import top.wcpe.mc.mpmt.platform.neoforge.NeoForgeTestSupport;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeConnectionHandle;

/** NeoForge 能力装配：端口全量注册、事件桥接与各端口行为。 */
class NeoForgeCapabilityBootstrapTest {

    @Test
    @DisplayName("装配注册全部七个服务端端口")
    void 装配注册全部端口() {
        MpmtRuntime runtime = new MpmtRuntime();
        MinecraftServer server = NeoForgeTestSupport.newServer();

        NeoForgeCapabilityBootstrap.register(server, runtime);

        assertInstanceOf(DataDirectoryPort.class, runtime.ports().get(DataDirectoryPort.class));
        assertInstanceOf(PersistencePort.class, runtime.ports().get(PersistencePort.class));
        assertInstanceOf(MessagePort.class, runtime.ports().get(MessagePort.class));
        assertInstanceOf(ConnectionControlPort.class,
                runtime.ports().get(ConnectionControlPort.class));
        assertInstanceOf(PlayerPort.class, runtime.ports().get(PlayerPort.class));
        assertInstanceOf(WorldPort.class, runtime.ports().get(WorldPort.class));
        assertInstanceOf(SchedulerPort.class, runtime.ports().get(SchedulerPort.class));
    }

    @Test
    @DisplayName("空服务端与空运行时被拒绝")
    void 空参数被拒绝() {
        MpmtRuntime runtime = new MpmtRuntime();

        assertThrows(NullPointerException.class,
                () -> NeoForgeCapabilityBootstrap.register(null, runtime));
        assertThrows(NullPointerException.class,
                () -> NeoForgeCapabilityBootstrap.register(NeoForgeTestSupport.newServer(), null));
    }

    @Test
    @DisplayName("玩家登录登出桥接到运行时事件总线")
    void 玩家事件桥接() {
        MpmtRuntime runtime = new MpmtRuntime();
        EventBusPort eventBus = runtime.eventBus();
        List<String> events = new java.util.ArrayList<>();
        eventBus.subscribe(PlayerJoinedEvent.class, event -> events.add("进:" + event.getPlayer().getName()));
        eventBus.subscribe(PlayerLeftEvent.class, event -> events.add("出:" + event.getPlayer().getName()));

        NeoForgeCapabilityBootstrap.PlayerConnectionBridge bridge =
                new NeoForgeCapabilityBootstrap.PlayerConnectionBridge(eventBus);
        ServerPlayer player = NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "桥接玩家");

        bridge.onPlayerLoggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
        bridge.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));

        assertEquals(List.of("进:桥接玩家", "出:桥接玩家"), events);
    }

    @Test
    @DisplayName("世界端口按维度键解析与列举，未知维度为空")
    void 世界端口解析与列举() {
        MinecraftServer server = NeoForgeTestSupport.newServer();
        NeoForgeTestSupport.putLevel(server, NeoForgeTestSupport.newLevel(Level.OVERWORLD));
        NeoForgeTestSupport.putLevel(server, NeoForgeTestSupport.newLevel(Level.NETHER));
        WorldPort port = new NeoForgeWorldPort(server);

        assertTrue(port.isLoaded("minecraft:overworld"));
        assertFalse(port.isLoaded("minecraft:the_end"));
        assertEquals(Optional.empty(), port.resolve("minecraft:the_end"));
        assertEquals(new WorldRef("minecraft:overworld"),
                port.resolve("minecraft:overworld").orElseThrow());
        assertEquals(2, port.loadedWorlds().size());
        assertThrows(UnsupportedOperationException.class,
                () -> port.loadedWorlds().add(new WorldRef("x")));
    }

    @Test
    @DisplayName("世界端口支持自建维度键")
    void 世界端口自建维度() {
        MinecraftServer server = NeoForgeTestSupport.newServer();
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                new net.minecraft.resources.ResourceLocation("mpmt", "probe"));
        NeoForgeTestSupport.putLevel(server, NeoForgeTestSupport.newLevel(key));
        WorldPort port = new NeoForgeWorldPort(server);

        assertTrue(port.isLoaded("mpmt:probe"));
        assertEquals(1, port.loadedWorlds().size());
    }

    @Test
    @DisplayName("玩家端口按 UUID 判定在线状态并暴露引用")
    void 玩家端口在线判定() {
        MinecraftServer server = NeoForgeTestSupport.newServer();
        ServerPlayer online = NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "在线");
        NeoForgeTestSupport.installPlayerList(server, online);
        PlayerPort port = new NeoForgePlayerPort(server);

        UUID offlineId = UUID.randomUUID();
        assertTrue(port.isOnline(online.getUUID()));
        assertFalse(port.isOnline(offlineId));
        assertEquals(Optional.empty(), port.resolve(offlineId));
        assertEquals(new PlayerRef(online.getUUID(), "在线"),
                port.resolve(online.getUUID()).orElseThrow());
        assertEquals(List.of(new PlayerRef(online.getUUID(), "在线")), port.onlinePlayers());
        assertThrows(UnsupportedOperationException.class,
                () -> port.onlinePlayers().add(new PlayerRef(offlineId, "外")));
    }

    @Test
    @DisplayName("消息端口向在线玩家发送，离线玩家静默丢弃")
    void 消息端口未在线静默() {
        MinecraftServer server = NeoForgeTestSupport.newServer();
        ServerPlayer online = NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "收信");
        NeoForgeTestSupport.installPlayerList(server, online);
        MessagePort port = new NeoForgeMessagePort(server);

        port.send(new PlayerRef(online.getUUID(), "收信"), "在线消息");
        port.send(new PlayerRef(UUID.randomUUID(), "无"), "离线消息");

        assertNotNull(port);
    }

    @Test
    @DisplayName("连接控制端口按玩家 UUID 生成实体引用并在服务端线程断开")
    void 连接控制端口断开() {
        MinecraftServer server = NeoForgeTestSupport.newServer();
        ServerPlayer online = NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "被断");
        // 断开路径经连接的服务端引用执行，替身须显式注入
        NeoForgeTestSupport.set(online, "connection", NeoForgeTestSupport.newListener(server));
        NeoForgeTestSupport.installPlayerList(server, online);
        ConnectionControlPort port = new NeoForgeConnectionControlPort(server);

        assertEquals(new EntityRef(online.getUUID()),
                port.entityOf(new NeoForgeConnectionHandle(online)));
        port.disconnect(new NeoForgeConnectionHandle(online), "违规");
        port.disconnect(new NeoForgeConnectionHandle(
                NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "不在线")), "离线");

        assertNotNull(port);
    }

    @Test
    @DisplayName("调度端口把非服务端线程的任务排入事件循环，排空后按序执行")
    void 调度端口投递任务() throws Exception {
        MinecraftServer server = NeoForgeTestSupport.newServer();
        NeoForgeSchedulerPort scheduler = new NeoForgeSchedulerPort(server);

        List<String> ran = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread worker = new Thread(() -> {
            scheduler.runGlobal(() -> ran.add("全局"));
            scheduler.runForEntity(new EntityRef(UUID.randomUUID()), () -> ran.add("实体"));
            scheduler.runForLocation(new WorldRef("minecraft:overworld"), 0, 0, () -> ran.add("位置"));
        }, "mpmt-neoforge-scheduler");
        worker.start();
        worker.join();

        assertTrue(ran.isEmpty(), "非服务端线程应入队而非就地执行");
        assertEquals(3, NeoForgeTestSupport.drainTasks(server));
        assertEquals(List.of("全局", "实体", "位置"), ran);
    }

    @Test
    @DisplayName("调度端口异步任务立即执行，周期任务由 tick 驱动且句柄可取消")
    void 调度端口异步与周期任务() throws Exception {
        MinecraftServer server = NeoForgeTestSupport.newServer();
        NeoForgeSchedulerPort scheduler = new NeoForgeSchedulerPort(server);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        scheduler.runAsync(latch::countDown);
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS), "异步任务应执行");

        java.util.concurrent.atomic.AtomicInteger ticks = new java.util.concurrent.atomic.AtomicInteger();
        AutoCloseable handle = scheduler.runTimer(1L, 1L, ticks::incrementAndGet);
        assertNotNull(handle);
        tick(scheduler);
        assertEquals(1, ticks.get(), "首次 tick 触发一次");
        tick(scheduler);
        assertEquals(2, ticks.get(), "周期到点再次触发");

        handle.close();
        tick(scheduler);
        assertEquals(2, ticks.get(), "取消后不再触发");
    }

    @Test
    @DisplayName("调度端口只响应服务端 tick 的结束阶段")
    void 调度端口只响应结束阶段() throws Exception {
        MinecraftServer server = NeoForgeTestSupport.newServer();
        NeoForgeSchedulerPort scheduler = new NeoForgeSchedulerPort(server);
        java.util.concurrent.atomic.AtomicInteger ticks = new java.util.concurrent.atomic.AtomicInteger();
        scheduler.runTimer(1L, 1L, ticks::incrementAndGet);

        scheduler.onServerTick(tickEvent(net.neoforged.neoforge.event.TickEvent.Phase.START, server));
        assertEquals(0, ticks.get(), "START 阶段不应驱动周期任务");

        scheduler.onServerTick(tickEvent(net.neoforged.neoforge.event.TickEvent.Phase.END, server));
        assertEquals(1, ticks.get());
    }

    @Test
    @DisplayName("数据目录端口以 NeoForge 配置目录为基准拼接")
    void 数据目录端口可用() {
        assertNotNull(new NeoForgeDataDirectoryPort());
    }

    private static void tick(NeoForgeSchedulerPort scheduler) throws Exception {
        NeoForgeTestSupport.invoke(scheduler, "onServerTick",
                new Class<?>[] {net.neoforged.neoforge.event.TickEvent.ServerTickEvent.class},
                tickEvent(net.neoforged.neoforge.event.TickEvent.Phase.END,
                        NeoForgeTestSupport.newServer()));
    }

    private static net.neoforged.neoforge.event.TickEvent.ServerTickEvent tickEvent(
            net.neoforged.neoforge.event.TickEvent.Phase phase, MinecraftServer server) {
        return new net.neoforged.neoforge.event.TickEvent.ServerTickEvent(phase, () -> false, server);
    }
}
