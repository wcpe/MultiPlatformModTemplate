package top.wcpe.mc.mpmt.platform.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeConnectionHandle;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeServerTransport;
import top.wcpe.mc.mpmt.platform.forge.version.ForgeServerNetwork;
import top.wcpe.mc.mpmt.platform.forge.version.v1_20.V1_20ServerNetwork;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * Forge mod 主类：版本探测、事件订阅回调与静态验收入口的真实行为。
 *
 * <p>构造期需要 Forge 运行期版本元数据（FML 加载器持有），故测试注入版本替身；主类的
 * {@code ServerStartedEvent} 装配链会经真实 SPI 发现、端口注册与服务端闭环安装。
 */
class MpmtForgeModTest {

    @BeforeAll
    static void 注入运行期元数据() {
        ForgeMinecraftTestSupport.installVersionInfo("1.20.1", "47.4.2");
        ForgeMinecraftTestSupport.installEmptyModList();
        ForgeMinecraftTestSupport.resetNetworkRegistry();
    }

    @BeforeEach
    void 隔离平台全局状态() {
        ForgeMinecraftTestSupport.resetNetworkRegistry();
        PlatformProvider.deactivate();
    }

    @AfterEach
    void 复原平台全局状态() {
        ForgeMinecraftTestSupport.resetNetworkRegistry();
        PlatformProvider.deactivate();
    }

    @Test
    @DisplayName("运行期探测命中 1.20 锚点，通道标识为 mpmt:main")
    void 探测命中锚点() {
        ForgeServerNetwork network = MpmtForgeMod.detectServerNetwork();

        assertInstanceOf(V1_20ServerNetwork.class, network);
        assertEquals(ResourceLocation.tryBuild("mpmt", "main"), network.channelId());
        assertEquals(1048576, network.maxPayloadSize());
        assertThrows(NullPointerException.class, () -> MpmtForgeMod.detectServerNetwork(null));
    }

    @Test
    @DisplayName("未知运行期版本明确失败而非静默退化")
    void 未知版本失败快() {
        assertThrows(IllegalStateException.class, () -> MpmtForgeMod.detectServerNetwork(() -> "1.21.1"));
        assertThrows(IllegalStateException.class, () -> MpmtForgeMod.detectServerNetwork(() -> ""));
    }

    @Test
    @DisplayName("构造期选择服务端代理并登记活跃传输")
    void 构造期登记传输() {
        MpmtForgeMod mod = new MpmtForgeMod();

        assertNotNull(mod);
        assertSame(ForgeServerTransport.class, MpmtForgeMod.serverTransport().getClass());
        assertEquals(ResourceLocation.tryBuild("mpmt", "main"), MpmtForgeMod.serverTransport().channelId());
    }

    @Test
    @DisplayName("服务端启动装配闭环：端口、封禁服务、网络特性与平台持有者")
    void 启动装配闭环() {
        MpmtForgeMod mod = new MpmtForgeMod();

        mod.onServerStarted(new net.minecraftforge.event.server.ServerStartedEvent(server()));

        assertEquals("forge", PlatformProvider.get().platformId());
        assertNotNull(MpmtForgeMod.serverNetworkFeature());
        assertNotNull(MpmtForgeMod.serverNetworkFeature().sessionRegistry());
        assertNotNull(MpmtForgeMod.serverNetworkFeature().handshakeService());
        assertEquals("server-network", MpmtForgeMod.serverNetworkFeature().name());
    }

    @Test
    @DisplayName("活跃产品传输需要已解析的物理连接，缺失连接时明确失败")
    void 活跃传输缺连接时失败() {
        MpmtForgeMod mod = new MpmtForgeMod();
        mod.onServerStarted(new net.minecraftforge.event.server.ServerStartedEvent(server()));
        ServerPlayer unbound = ForgeMinecraftTestSupport.newPlayer(UUID.randomUUID(), "未绑定连接");

        assertThrows(RuntimeException.class, () -> MpmtForgeMod.sendActive(unbound, new byte[] {1}));
    }

    @Test
    @DisplayName("玩家进出事件在闭环就绪后登记 / 清理连接，未就绪时静默忽略")
    void 玩家事件进出() {
        MpmtForgeMod mod = new MpmtForgeMod();
        ServerPlayer player = ForgeMinecraftTestSupport.newPlayer(UUID.randomUUID(), "玩家事件");

        // 尚未装配：事件被忽略，且不抛异常
        mod.onPlayerLoggedIn(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player));
        mod.onPlayerLoggedOut(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));

        mod.onServerStarted(new net.minecraftforge.event.server.ServerStartedEvent(server()));
        mod.onPlayerLoggedIn(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player));
        ForgeConnectionHandle handle = MpmtForgeMod.serverTransport().connectionFor(player);
        assertEquals(player.getUUID(), handle.playerId());

        mod.onPlayerLoggedOut(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
        assertNull(MpmtForgeMod.serverTransport().onDisconnected(player));
    }

    @Test
    @DisplayName("服务端停止释放运行时、连接表与平台持有者")
    void 停止释放资源() {
        MpmtForgeMod mod = new MpmtForgeMod();
        mod.onServerStarted(new net.minecraftforge.event.server.ServerStartedEvent(server()));
        assertNotNull(PlatformProvider.get());

        mod.onServerStopped(new net.minecraftforge.event.server.ServerStoppedEvent(server()));

        assertThrows(IllegalStateException.class, MpmtForgeMod::serverNetworkFeature);
        assertNotNull(MpmtForgeMod.serverTransport());
        // 未装配过运行时：停止事件不抛异常
        mod.onServerStopped(new net.minecraftforge.event.server.ServerStoppedEvent(server()));
    }

    @Test
    @DisplayName("注册命令事件把 Brigadier 分发器交给机器码命令")
    void 注册命令事件() {
        MpmtForgeMod mod = new MpmtForgeMod();
        com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher =
                new com.mojang.brigadier.CommandDispatcher<>();

        mod.onRegisterCommands(registerCommandsEvent(dispatcher));

        assertTrue(dispatcher.getRoot().getChildren().stream()
                .anyMatch(node -> node.getName().equals("mpmt")));
    }

    private static MinecraftServer server(ServerPlayer... players) {
        MinecraftServer server = ForgeMinecraftTestSupport.newServer();
        ForgeMinecraftTestSupport.installPlayerList(server, players);
        return server;
    }

    /** 构造命令注册事件：命令注册回调只取分发器，命令选择与构建上下文可留空。 */
    private static net.minecraftforge.event.RegisterCommandsEvent registerCommandsEvent(
            com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        return new net.minecraftforge.event.RegisterCommandsEvent(
                dispatcher, net.minecraft.commands.Commands.CommandSelection.ALL, null);
    }
}
