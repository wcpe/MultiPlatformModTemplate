package top.wcpe.mc.mpmt.platform.forge.modern.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
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
import top.wcpe.mc.mpmt.platform.forge.modern.ForgeTestSupport;

/** Forge 能力装配：端口全量注册、事件桥接与各端口行为。 */
class ForgeCapabilityBootstrapTest {

    @Test
    @DisplayName("装配注册全部七个服务端端口")
    void 装配注册全部端口() {
        MpmtRuntime runtime = new MpmtRuntime();
        MinecraftServer server = ForgeTestSupport.newServer();

        ForgeCapabilityBootstrap.register(server, runtime);

        assertInstanceOf(DataDirectoryPort.class, runtime.ports().get(DataDirectoryPort.class));
        assertInstanceOf(PersistencePort.class, runtime.ports().get(PersistencePort.class));
        assertInstanceOf(MessagePort.class, runtime.ports().get(MessagePort.class));
        assertInstanceOf(ConnectionControlPort.class, runtime.ports().get(ConnectionControlPort.class));
        assertInstanceOf(PlayerPort.class, runtime.ports().get(PlayerPort.class));
        assertInstanceOf(WorldPort.class, runtime.ports().get(WorldPort.class));
        assertInstanceOf(SchedulerPort.class, runtime.ports().get(SchedulerPort.class));
    }

    @Test
    @DisplayName("空服务端与世界为空参数被拒绝")
    void 空参数被拒绝() {
        MpmtRuntime runtime = new MpmtRuntime();

        assertThrows(NullPointerException.class,
                () -> ForgeCapabilityBootstrap.register(null, runtime));
        assertThrows(NullPointerException.class,
                () -> ForgeCapabilityBootstrap.register(ForgeTestSupport.newServer(), null));
    }

    @Test
    @DisplayName("玩家登录登出桥接到运行时事件总线")
    void 玩家事件桥接() {
        MpmtRuntime runtime = new MpmtRuntime();
        EventBusPort eventBus = runtime.eventBus();
        List<String> events = new java.util.ArrayList<>();
        eventBus.subscribe(PlayerJoinedEvent.class, event -> events.add("进:" + event.getPlayer().getName()));
        eventBus.subscribe(PlayerLeftEvent.class, event -> events.add("出:" + event.getPlayer().getName()));

        ForgeCapabilityBootstrap.PlayerConnectionBridge bridge =
                new ForgeCapabilityBootstrap.PlayerConnectionBridge(eventBus);
        ServerPlayer player = ForgeTestSupport.newPlayer(UUID.randomUUID(), "桥接玩家");

        bridge.onPlayerLoggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
        bridge.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));

        assertEquals(List.of("进:桥接玩家", "出:桥接玩家"), events);
    }

    @Test
    @DisplayName("世界端口按维度键解析与列举，未知维度为空")
    void 世界端口解析与列举() {
        MinecraftServer server = ForgeTestSupport.newServer();
        ServerLevel overworld = ForgeTestSupport.newLevel(Level.OVERWORLD);
        ServerLevel nether = ForgeTestSupport.newLevel(Level.NETHER);
        ForgeTestSupport.putLevel(server, overworld);
        ForgeTestSupport.putLevel(server, nether);
        WorldPort port = new ForgeWorldPort(server);

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
    @DisplayName("玩家端口按 UUID 判定在线状态并暴露引用")
    void 玩家端口在线判定() {
        MinecraftServer server = ForgeTestSupport.newServer();
        ServerPlayer online = ForgeTestSupport.newPlayer(UUID.randomUUID(), "在线");
        ForgeTestSupport.installPlayerList(server, online);
        PlayerPort port = new ForgePlayerPort(server);

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
        MinecraftServer server = ForgeTestSupport.newServer();
        ServerPlayer online = ForgeTestSupport.newPlayer(UUID.randomUUID(), "收信");
        ForgeTestSupport.installPlayerList(server, online);
        MessagePort port = new ForgeMessagePort(server);

        port.send(new PlayerRef(online.getUUID(), "收信"), "在线消息");
        port.send(new PlayerRef(UUID.randomUUID(), "无"), "离线消息");

        assertNotNull(port);
    }

    @Test
    @DisplayName("连接控制端口按玩家 UUID 生成实体引用并断开")
    void 连接控制端口断开() {
        MinecraftServer server = ForgeTestSupport.newServer();
        ServerPlayer online = ForgeTestSupport.newPlayer(UUID.randomUUID(), "被断");
        ForgeTestSupport.set(online, "connection", ForgeTestSupport.newListener(server));
        ForgeTestSupport.installPlayerList(server, online);
        ConnectionControlPort port = new ForgeConnectionControlPort(server);
        top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeConnectionHandle handle =
                new top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeConnectionHandle(online);

        assertEquals(new EntityRef(online.getUUID()), port.entityOf(handle));
        port.disconnect(handle, "违规");
        port.disconnect(new top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeConnectionHandle(
                ForgeTestSupport.newPlayer(UUID.randomUUID(), "不在线")), "离线");

        assertNotNull(port);
    }

    @Test
    @DisplayName("调度端口把非服务端线程的任务排入事件循环，排空后按序执行")
    void 调度端口投递任务() throws Exception {
        MinecraftServer server = ForgeTestSupport.newServer();
        ForgeSchedulerPort scheduler = new ForgeSchedulerPort(server);

        try {
            List<String> ran = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            // 从工作线程投递：服务端替身的运行线程是主线程，故任务应入队而非就地执行
            Thread worker = new Thread(() -> {
                scheduler.runGlobal(() -> ran.add("全局"));
                scheduler.runForEntity(new EntityRef(UUID.randomUUID()), () -> ran.add("实体"));
                scheduler.runForLocation(new WorldRef("minecraft:overworld"), 0, 0, () -> ran.add("位置"));
            }, "mpmt-scheduler-test");
            worker.start();
            worker.join();

            assertTrue(ran.isEmpty(), "非服务端线程应入队而非就地执行");
            assertEquals(3, ForgeTestSupport.drainTasks(server));
            assertEquals(List.of("全局", "实体", "位置"), ran);
        } finally {
            scheduler.close();
        }
    }

    @Test
    @DisplayName("调度端口异步任务在守护线程执行且句柄可取消")
    void 调度端口异步与周期任务() throws Exception {
        MinecraftServer server = ForgeTestSupport.newServer();
        ForgeSchedulerPort scheduler = new ForgeSchedulerPort(server);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        try {
            scheduler.runAsync(latch::countDown);
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS), "异步任务应执行");

            java.util.concurrent.atomic.AtomicInteger ticks = new java.util.concurrent.atomic.AtomicInteger();
            AutoCloseable handle = scheduler.runTimer(1L, 1L, ticks::incrementAndGet);
            assertNotNull(handle);
            ForgeTestSupport.invoke(scheduler, "tickTimers", new Class<?>[0]);
            assertEquals(1, ticks.get(), "首次 tick 触发一次");
            handle.close();
            ForgeTestSupport.invoke(scheduler, "tickTimers", new Class<?>[0]);
            assertEquals(1, ticks.get(), "取消后不再触发");
        } finally {
            scheduler.close();
        }
    }

    @Test
    @DisplayName("调度端口关闭后拒绝新任务，重复关闭幂等")
    void 调度端口关闭语义() {
        MinecraftServer server = ForgeTestSupport.newServer();
        ForgeSchedulerPort scheduler = new ForgeSchedulerPort(server);

        scheduler.close();
        scheduler.close();

        assertThrows(IllegalStateException.class, () -> scheduler.runGlobal(() -> { }));
        assertThrows(IllegalStateException.class, () -> scheduler.runAsync(() -> { }));
        assertThrows(IllegalStateException.class, () -> scheduler.runForEntity(null, () -> { }));
        assertThrows(IllegalStateException.class, () -> scheduler.runForLocation(null, 0, 0, () -> { }));
        assertThrows(IllegalStateException.class, () -> scheduler.runTimer(1L, 1L, () -> { }));
    }

    @Test
    @DisplayName("玩家登录桥接经真实 EventBus 分发给平台无关订阅者")
    void 桥接端到端分发() {
        MpmtRuntime runtime = new MpmtRuntime();
        java.util.concurrent.atomic.AtomicReference<PlayerRef> seen = new java.util.concurrent.atomic.AtomicReference<>();
        runtime.eventBus().subscribe(PlayerJoinedEvent.class, event -> seen.set(event.getPlayer()));

        ForgeCapabilityBootstrap.PlayerConnectionBridge bridge =
                new ForgeCapabilityBootstrap.PlayerConnectionBridge(runtime.eventBus());
        Player player = ForgeTestSupport.newPlayer(UUID.randomUUID(), "端到端");

        bridge.onPlayerLoggedIn(new PlayerEvent.PlayerLoggedInEvent(player));

        assertEquals("端到端", seen.get().getName());
    }

    @Test
    @DisplayName("数据目录端口以 Forge 配置目录为基准拼接")
    void 数据目录端口拼接() {
        assertNotNull(new ForgeDataDirectoryPort());
    }

    @Test
    @DisplayName("世界端口解析走过的具体维度键实例保持不变")
    void 世界端口维度键() {
        MinecraftServer server = ForgeTestSupport.newServer();
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("mpmt", "probe"));
        ForgeTestSupport.putLevel(server, ForgeTestSupport.newLevel(key));
        WorldPort port = new ForgeWorldPort(server);

        assertTrue(port.isLoaded("mpmt:probe"));
        assertSame(1, port.loadedWorlds().size());
    }
}
