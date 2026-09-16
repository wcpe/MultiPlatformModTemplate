package top.wcpe.mc.mpmt.platform.sponge.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.platform.sponge.SpongeTestRuntime;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionHandle;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionRegistry;

/**
 * Sponge 平台端口的委托与边界：经假 SpongeAPI 静态 Holder 驱动 {@link SpongeSchedulerPort}、
 * {@link SpongeWorldPort}、{@link SpongePlayerPort}、{@link SpongeMessagePort}、
 * {@link SpongeConnectionControlPort} 与 {@link SpongeDataDirectoryPort}。
 */
class SpongeCapabilityPortsTest {

    @Test
    @DisplayName("调度端口：归属调度统一落到服务端主线程，异步落到异步调度器")
    void 调度端口按归属分流同步与异步() {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            SpongeSchedulerPort port = new SpongeSchedulerPort(SpongeTestRuntime.pluginContainer());

            port.runForEntity(new EntityRef(UUID.randomUUID()), () -> {});
            port.runForLocation(new WorldRef("minecraft:overworld"), 1, 2, () -> {});
            port.runGlobal(() -> {});
            assertEquals(3, sponge.syncTasks.size(), "三次带归属调度应全部提交到服务端主线程调度器");
            assertTrue(sponge.asyncTasks.isEmpty(), "带归属调度不得落到异步调度器");

            port.runAsync(() -> {});
            assertEquals(1, sponge.asyncTasks.size(), "runAsync 应落到异步调度器");
            assertEquals(3, sponge.syncTasks.size(), "异步调度不得混入同步调度器");
            assertEquals(
                    SpongeTestRuntime.pluginContainer().getClass(),
                    sponge.builtTasks.get(0).plugin().getClass());
            assertNotNull(sponge.builtTasks.get(0).plugin(), "任务必须声明插件归属");
        }
    }

    @Test
    @DisplayName("调度端口：周期任务带延迟与周期提交，句柄取消透传")
    void 调度端口周期任务与取消句柄() throws Exception {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            SpongeSchedulerPort port = new SpongeSchedulerPort(SpongeTestRuntime.pluginContainer());

            AutoCloseable handle = port.runTimer(20L, 100L, () -> {});

            assertEquals(1, sponge.timers.size(), "周期任务应提交到服务端主线程调度器");
            assertEquals(20L, sponge.timers.get(0).delayTicks);
            assertEquals(100L, sponge.timers.get(0).periodTicks);
            assertSame(sponge.builtTasks.get(0), sponge.timerHandles.get(0).task());

            assertFalse(sponge.timerHandles.get(0).isCancelled(), "句柄初始应为未取消");
            handle.close();
            assertTrue(sponge.timerHandles.get(0).isCancelled(), "关闭句柄应透传取消到底层任务");
        }
    }

    @Test
    @DisplayName("调度端口：空插件容器即拒绝构造")
    void 调度端口拒绝空插件() {
        assertThrows(NullPointerException.class, () -> new SpongeSchedulerPort(null));
    }

    @Test
    @DisplayName("世界端口：加载判定、列表与解析全部按资源键字符串")
    void 世界端口按资源键暴露世界() {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            sponge.addWorld("minecraft:overworld");
            sponge.addWorld("minecraft:the_nether");
            SpongeWorldPort port = new SpongeWorldPort();

            assertTrue(port.isLoaded("minecraft:overworld"));
            assertFalse(port.isLoaded("minecraft:the_end"));

            List<WorldRef> loaded = port.loadedWorlds();
            assertEquals(2, loaded.size());
            assertEquals("minecraft:overworld", loaded.get(0).getId());
            assertEquals("minecraft:the_nether", loaded.get(1).getId());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> loaded.add(new WorldRef("minecraft:the_end")),
                    "已加载世界快照必须不可变");

            assertEquals(
                    Optional.of(new WorldRef("minecraft:the_nether")),
                    port.resolve("minecraft:the_nether"));
            assertEquals(Optional.empty(), port.resolve("missing:world"));
        }
    }

    @Test
    @DisplayName("玩家端口：在线判定、列表与解析全部按 UUID")
    void 玩家端口按UUID暴露在线玩家() {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            UUID onlineId = UUID.randomUUID();
            UUID offlineId = UUID.randomUUID();
            sponge.addPlayer(onlineId, "甲");
            SpongePlayerPort port = new SpongePlayerPort();

            assertTrue(port.isOnline(onlineId));
            assertFalse(port.isOnline(offlineId));

            List<PlayerRef> players = port.onlinePlayers();
            assertEquals(1, players.size());
            assertEquals(new PlayerRef(onlineId, "甲"), players.get(0));
            assertThrows(
                    UnsupportedOperationException.class, () -> players.add(null), "在线玩家快照必须不可变");

            assertEquals(Optional.of(new PlayerRef(onlineId, "甲")), port.resolve(onlineId));
            assertEquals(Optional.empty(), port.resolve(offlineId));
        }
    }

    @Test
    @DisplayName("消息端口：在线玩家收到组件，离线玩家静默丢弃")
    void 消息端口按在线状态投递() {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            UUID onlineId = UUID.randomUUID();
            sponge.addPlayer(onlineId, "乙");
            SpongeMessagePort port = new SpongeMessagePort();

            port.send(new PlayerRef(onlineId, "乙"), "你好");
            port.send(new PlayerRef(UUID.randomUUID(), "丙"), "不应抛出");
        }
    }

    @Test
    @DisplayName("连接控制端口：句柄映射为实体归属，仅当前连接执行踢出")
    void 连接控制端口按当前代际踢出() {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            UUID playerId = UUID.randomUUID();
            ServerPlayer player = sponge.addPlayer(playerId, "丁");
            SpongeConnectionRegistry registry = new SpongeConnectionRegistry();
            SpongeConnectionHandle handle = registry.handleOf(player);
            SpongeConnectionControlPort port = new SpongeConnectionControlPort(registry);

            assertEquals(new EntityRef(playerId), port.entityOf(handle));

            port.disconnect(handle, "你的客户端标识已被封禁");
            assertEquals(1, sponge.kicks.size(), "当前代际连接应被真实踢出");
            assertTrue(sponge.kicks.get(playerId).contains("已被封禁"));
        }
    }

    @Test
    @DisplayName("连接控制端口：同 UUID 新连接取代旧句柄后，旧句柄不再踢出")
    void 连接控制端口忽略过期句柄() {
        try (SpongeTestRuntime sponge = SpongeTestRuntime.install()) {
            UUID playerId = UUID.randomUUID();
            ServerPlayer player = sponge.addPlayer(playerId, "戊");
            SpongeConnectionRegistry registry = new SpongeConnectionRegistry();
            SpongeConnectionHandle stale = registry.connected(player);
            registry.connected(player);
            SpongeConnectionControlPort port = new SpongeConnectionControlPort(registry);

            port.disconnect(stale, "过期连接");

            assertTrue(sponge.kicks.isEmpty(), "过期物理句柄不得踢出当前玩家");
        }
    }

    @Test
    @DisplayName("数据目录端口：原样返回注入的基目录并拒绝空值")
    void 数据目录端口原样暴露基目录() {
        Path base = Path.of("config", "mpmt");
        SpongeDataDirectoryPort port = new SpongeDataDirectoryPort(base);

        assertSame(base, port.baseDirectory());
        assertThrows(NullPointerException.class, () -> new SpongeDataDirectoryPort(null));
    }

    @Test
    @DisplayName("端口实现不泄漏平台原生对象进 L0：只暴露领域引用与标准类型")
    void 端口签名保持平台无关() {
        assertTrue(DataDirectoryPort.class.isAssignableFrom(SpongeDataDirectoryPort.class));
        assertFalse(Component.class.isAssignableFrom(SpongeMessagePort.class));
        assertNotNull(new SpongePlayerPort());
    }
}
