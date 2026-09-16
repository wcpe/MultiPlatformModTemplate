package top.wcpe.mc.mpmt.platform.sponge.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.plugin.PluginContainer;
import top.wcpe.mc.mpmt.core.domain.event.DomainEvent;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.PlayerPort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.WorldPort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;
import top.wcpe.mc.mpmt.domain.capability.PlayerLeftEvent;
import top.wcpe.mc.mpmt.platform.sponge.SpongeTestRuntime;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionHandle;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionRegistry;

/**
 * {@link SpongeCapabilityBootstrap} 的端口装配与玩家连接事件桥接（纯 JVM，经假 SpongeAPI 静态 Holder）。
 */
class SpongeCapabilityBootstrapTest {

    @Test
    @DisplayName("装配：注册全部服务端能力端口与物理连接登记表")
    void 装配注册全部端口() {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            sponge.runSyncTasks();
            assertTrue(sponge.isInstalled(), "测试替身应在本次用例内替换静态 Sponge");
            Path configDir = Path.of("config", "mpmt");
            MpmtRuntime runtime = new MpmtRuntime();
            SpongeConnectionRegistry registry = new SpongeConnectionRegistry();

            SpongeCapabilityBootstrap.register(
                    SpongeTestRuntime.pluginContainer(), configDir, runtime, registry);

            assertSame(configDir, runtime.ports().get(DataDirectoryPort.class).baseDirectory());
            assertTrue(runtime.ports().contains(PersistencePort.class));
            assertTrue(runtime.ports().contains(MessagePort.class));
            assertTrue(runtime.ports().contains(ConnectionControlPort.class));
            assertTrue(runtime.ports().contains(PlayerPort.class));
            assertTrue(runtime.ports().contains(WorldPort.class));
            assertTrue(runtime.ports().contains(SchedulerPort.class));
            assertSame(registry, runtime.ports().get(SpongeConnectionRegistry.class));
            assertEquals(1, sponge.registeredListeners.size(), "应注册玩家连接事件桥");
            assertTrue(
                    sponge.registeredListeners.get(0)
                            instanceof SpongeCapabilityBootstrap.PlayerConnectionBridge);
        }
    }

    @Test
    @DisplayName("装配：四个入参任一为空即拒绝")
    void 装配拒绝空入参() {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            sponge.runSyncTasks();
            assertTrue(sponge.isInstalled(), "测试替身应在本次用例内替换静态 Sponge");
            Path configDir = Path.of("config", "mpmt");
            PluginContainer plugin = SpongeTestRuntime.pluginContainer();
            MpmtRuntime runtime = new MpmtRuntime();
            SpongeConnectionRegistry registry = new SpongeConnectionRegistry();

            assertThrows(
                    NullPointerException.class,
                    () -> SpongeCapabilityBootstrap.register(null, configDir, runtime, registry));
            assertThrows(
                    NullPointerException.class,
                    () -> SpongeCapabilityBootstrap.register(plugin, null, runtime, registry));
            assertThrows(
                    NullPointerException.class,
                    () -> SpongeCapabilityBootstrap.register(plugin, configDir, null, registry));
            assertThrows(
                    NullPointerException.class,
                    () -> SpongeCapabilityBootstrap.register(plugin, configDir, runtime, null));
        }
    }

    @Test
    @DisplayName("装配：单参重载自行创建物理连接登记表并完成装配")
    void 单参重载自建登记表() {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            sponge.runSyncTasks();
            assertTrue(sponge.isInstalled(), "测试替身应在本次用例内替换静态 Sponge");
            MpmtRuntime runtime = new MpmtRuntime();

            SpongeCapabilityBootstrap.register(
                    SpongeTestRuntime.pluginContainer(), Path.of("config", "mpmt"), runtime);

            assertTrue(runtime.ports().contains(SpongeConnectionRegistry.class));
            assertTrue(runtime.ports().contains(SchedulerPort.class));
        }
    }

    @Test
    @DisplayName("事件桥：加入事件转成 PlayerJoinedEvent 投递到 EventBus")
    void 加入事件桥接到领域事件() {
        List<DomainEvent> received = new ArrayList<>();
        MpmtRuntime runtime = new MpmtRuntime();
        SpongeCapabilityBootstrap.PlayerConnectionBridge bridge =
                new SpongeCapabilityBootstrap.PlayerConnectionBridge(runtime.eventBus());
        runtime.eventBus().subscribe(PlayerJoinedEvent.class, received::add);

        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            UUID playerId = UUID.randomUUID();
            ServerPlayer player = sponge.addPlayer(playerId, "己");
            bridge.onJoin(joinEvent(player));
        }

        assertEquals(1, received.size());
        PlayerJoinedEvent event = (PlayerJoinedEvent) received.get(0);
        assertEquals("己", event.getPlayer().getName());
    }

    @Test
    @DisplayName("事件桥：断开事件按档案转成 PlayerLeftEvent（含离线档案名）")
    void 断开事件桥接到领域事件() {
        List<DomainEvent> received = new ArrayList<>();
        MpmtRuntime runtime = new MpmtRuntime();
        SpongeCapabilityBootstrap.PlayerConnectionBridge bridge =
                new SpongeCapabilityBootstrap.PlayerConnectionBridge(runtime.eventBus());
        runtime.eventBus().subscribe(PlayerLeftEvent.class, received::add);

        UUID playerId = UUID.randomUUID();
        bridge.onDisconnect(disconnectEvent(playerId, "庚"));

        assertEquals(1, received.size());
        PlayerLeftEvent left = (PlayerLeftEvent) received.get(0);
        assertEquals(playerId, left.getPlayer().getUuid());
        assertEquals("庚", left.getPlayer().getName());
    }

    @Test
    @DisplayName("事件桥：无总线时投递失败快，不静默吞掉")
    void 事件桥无总线时失败快() {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            SpongeCapabilityBootstrap.PlayerConnectionBridge bridge =
                    new SpongeCapabilityBootstrap.PlayerConnectionBridge(null);
            ServerPlayer player = sponge.addPlayer(UUID.randomUUID(), "癸");

            assertThrows(NullPointerException.class, () -> bridge.onJoin(joinEvent(player)));
        }
    }

    @Test
    @DisplayName("玩家离开后其物理句柄被摘除，当前连接仍可查到")
    void 登记表在装配后仍可追踪当前连接() {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            UUID playerId = UUID.randomUUID();
            ServerPlayer player = sponge.addPlayer(playerId, "辛");
            SpongeConnectionRegistry registry = new SpongeConnectionRegistry();
            MpmtRuntime runtime = new MpmtRuntime();
            SpongeCapabilityBootstrap.register(
                    SpongeTestRuntime.pluginContainer(), Path.of("config", "mpmt"), runtime, registry);

            SpongeConnectionHandle connected = registry.connected(player);
            assertSame(connected, registry.current(playerId));

            ConnectionControlPort control = runtime.ports().get(ConnectionControlPort.class);
            assertEquals(playerId, control.entityOf(connected).getId());

            registry.disconnected(player);
            assertFalse(Optional.ofNullable(registry.current(playerId)).isPresent());
        }
    }

    @Test
    @DisplayName("装配后消息端口对离线玩家静默，不抛异常")
    void 装配后消息端口对离线玩家静默() {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            MpmtRuntime runtime = new MpmtRuntime();
            SpongeCapabilityBootstrap.register(
                    SpongeTestRuntime.pluginContainer(), Path.of("config", "mpmt"), runtime);

            runtime.ports().get(MessagePort.class).send(new PlayerRef(UUID.randomUUID(), "壬"), "离线不应抛出");
            assertTrue(sponge.onlinePlayers.isEmpty());
        }
    }

    private static ServerSideConnectionEvent.Join joinEvent(ServerPlayer player) {
        return SpongeTestRuntime.stub(
                ServerSideConnectionEvent.Join.class, SpongeTestRuntime.results("player", player));
    }

    private static ServerSideConnectionEvent.Disconnect disconnectEvent(UUID playerId, String name) {
        GameProfile profile =
                SpongeTestRuntime.stub(
                        GameProfile.class,
                        SpongeTestRuntime.results(
                                "uniqueId", playerId, "name", Optional.of(name)));
        return SpongeTestRuntime.stub(
                ServerSideConnectionEvent.Disconnect.class,
                SpongeTestRuntime.results("profile", profile));
    }
}
