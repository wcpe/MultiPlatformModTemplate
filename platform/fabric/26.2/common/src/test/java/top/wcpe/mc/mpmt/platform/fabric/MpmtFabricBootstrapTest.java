package top.wcpe.mc.mpmt.platform.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.platform.fabric.FabricTestSupport.LoaderStub;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricServerTransport;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * Fabric 主入口闭环：初始化注册回调 → 服务端启动装配 → 玩家进出桥接 → 停服清理。
 *
 * <p>入口的生命周期钩子为私有方法，测试按真实签名反射调用（仓库既有手法）；
 * MC 服务端替身走真实事件循环，被 {@code execute} 排队的收尾任务可确定性观测。
 */
class MpmtFabricBootstrapTest {

    @BeforeEach
    @AfterEach
    void 重置平台Holder() {
        // 静态 Holder 跨用例互相影响，逐例重置进程级绑定
        FabricTestSupport.resetPlatformProvider();
    }

    @Test
    @DisplayName("初始化注册服务端启动 / 停服与玩家连接 / 断线回调")
    void 初始化注册回调() {
        int startedBefore = listeners(ServerLifecycleEvents.SERVER_STARTED);
        int stoppedBefore = listeners(ServerLifecycleEvents.SERVER_STOPPED);
        int joinBefore = listeners(ServerPlayConnectionEvents.JOIN);
        int disconnectBefore = listeners(ServerPlayConnectionEvents.DISCONNECT);

        new MpmtFabricBootstrap().onInitialize();

        assertEquals(startedBefore + 1, listeners(ServerLifecycleEvents.SERVER_STARTED));
        assertEquals(stoppedBefore + 1, listeners(ServerLifecycleEvents.SERVER_STOPPED));
        assertEquals(joinBefore + 1, listeners(ServerPlayConnectionEvents.JOIN));
        assertEquals(disconnectBefore + 1, listeners(ServerPlayConnectionEvents.DISCONNECT));
    }

    @Test
    @DisplayName("服务端启动装配产品闭环：传输端口、服务端服务与平台绑定齐备")
    void 服务端启动装配闭环() {
        Fixture fixture = new Fixture();

        try {
            fixture.start();

            assertNotNull(MpmtFabricBootstrap.serverTransport());
            assertNotNull(MpmtFabricBootstrap.serverNetworkFeature());
            assertEquals("fabric", PlatformProvider.get().platformId());
            assertEquals(MpmtRuntime.Phase.ENABLED, fixture.runtime().phase());
        } finally {
            fixture.close();
        }
    }

    @Test
    @DisplayName("未装配前取传输与网络特性失败快，不返回残缺对象")
    void 未装配失败快() {
        assertThrows(IllegalStateException.class, MpmtFabricBootstrap::serverTransport);
        assertThrows(IllegalStateException.class, MpmtFabricBootstrap::serverNetworkFeature);
    }

    @Test
    @DisplayName("玩家连接在传输上建立句柄并登记握手状态；断线后握手与句柄一并清理")
    void 玩家连接与断线桥接() {
        Fixture fixture = new Fixture();

        try {
            fixture.start();
            FabricServerTransport transport = MpmtFabricBootstrap.serverTransport();
            ServerNetworkFeature feature = MpmtFabricBootstrap.serverNetworkFeature();
            ServerPlayer player = fixture.newPlayer("桥接玩家");

            connected(fixture.bootstrap(), player);
            FabricConnectionHandleProbe handle = new FabricConnectionHandleProbe(transport.connectionFor(player));
            assertSame(player, handle.player(), "连接句柄须包住当前物理玩家");
            assertNotNull(
                    feature.handshakeService().stateOf(handle.connection()), "连接建立后握手状态必须登记");

            disconnected(fixture.bootstrap(), player);
            assertNull(
                    feature.handshakeService().stateOf(handle.connection()), "断线后握手状态须清理");
            assertNull(transport.onDisconnected(player), "句柄已移除，重复移除返回空");
        } finally {
            fixture.close();
        }
    }

    @Test
    @DisplayName("停服清理：运行时停用、调度端口关闭、平台取消激活")
    void 停服清理释放资源() {
        Fixture fixture = new Fixture();

        try {
            fixture.start();
            MpmtRuntime runtime = fixture.runtime();
            SchedulerPort scheduler = runtime.ports().get(SchedulerPort.class);

            stop(fixture);

            assertEquals(MpmtRuntime.Phase.DISABLED, runtime.phase());
            assertNull(readRuntime(fixture.bootstrap()));
            assertFalse(PlatformProvider.isBooted());
            assertThrows(
                    IllegalStateException.class,
                    () -> scheduler.runGlobal(() -> { }),
                    "停服后调度器必须拒绝新任务");
        } finally {
            fixture.close();
        }
    }

    @Test
    @DisplayName("停服后重复停服与玩家回调安全，不抛错也不误报已装配")
    void 停服后回调幂等() {
        Fixture fixture = new Fixture();

        try {
            fixture.start();
            ServerPlayer player = fixture.newPlayer("停服后玩家");

            stop(fixture);
            stop(fixture);
            connected(fixture.bootstrap(), player);
            disconnected(fixture.bootstrap(), player);

            assertThrows(IllegalStateException.class, MpmtFabricBootstrap::serverTransport);
            assertFalse(PlatformProvider.isBooted());
        } finally {
            fixture.close();
        }
    }

    @Test
    @DisplayName("装配前玩家回调静默返回，不产生任何连接状态")
    void 装配前玩家回调静默返回() {
        MpmtFabricBootstrap bootstrap = new MpmtFabricBootstrap();
        ServerPlayer player = MinecraftTestSupport.newPlayer(UUID.randomUUID(), "未装配玩家");

        connected(bootstrap, player);
        disconnected(bootstrap, player);

        assertThrows(IllegalStateException.class, MpmtFabricBootstrap::serverTransport);
    }

    private static void connected(MpmtFabricBootstrap bootstrap, ServerPlayer player) {
        FabricTestSupport.invoke(
                bootstrap, "onPlayerConnected", new Class<?>[] {ServerPlayer.class}, player);
    }

    private static void disconnected(MpmtFabricBootstrap bootstrap, ServerPlayer player) {
        FabricTestSupport.invoke(
                bootstrap, "onPlayerDisconnected", new Class<?>[] {ServerPlayer.class}, player);
    }

    private static void stop(Fixture fixture) {
        FabricTestSupport.invoke(
                fixture.bootstrap(), "onServerStopped", new Class<?>[] {MinecraftServer.class}, fixture.server());
    }

    private static int listeners(net.fabricmc.fabric.api.event.Event<?> event) {
        return FabricTestSupport.listenersOf(event, Object.class).size();
    }

    private static Object readRuntime(MpmtFabricBootstrap bootstrap) {
        return FabricTestSupport.read(bootstrap, "runtime");
    }

    /** 一次性夹具：服务端替身 + 入口，负责启动与收尾。 */
    private static final class Fixture implements AutoCloseable {

        private final MinecraftServer server;
        private final MpmtFabricBootstrap bootstrap = new MpmtFabricBootstrap();
        private LoaderStub loaderStub;

        Fixture() {
            this.server = MinecraftTestSupport.newServer();
            MinecraftTestSupport.installPlayerList(server);
            // 记录注册前的监听器基线：本进程内先前用例的监听器仍在，触发时须排除
            this.startedBaseline = listeners(ServerLifecycleEvents.SERVER_STARTED);
            bootstrap.onInitialize();
        }

        private final int startedBaseline;

        MinecraftServer server() {
            return server;
        }

        MpmtFabricBootstrap bootstrap() {
            return bootstrap;
        }

        MpmtRuntime runtime() {
            return (MpmtRuntime) readRuntime(bootstrap);
        }

        ServerPlayer newPlayer(String name) {
            ServerPlayer player = MinecraftTestSupport.newPlayer(UUID.randomUUID(), name);
            MinecraftTestSupport.installPlayerList(server, player);
            return player;
        }

        /** 触发服务端启动钩子（产品要求运行期 MC 元数据可探测）。 */
        void start() {
            loaderStub = FabricTestSupport.stubMinecraftVersion(System.getProperty("mpmt.test.minecraftVersion"));
            int fired = FabricTestSupport.fireFrom(
                    ServerLifecycleEvents.SERVER_STARTED,
                    ServerLifecycleEvents.ServerStarted.class,
                    startedBaseline,
                    listener -> listener.onServerStarted(server));
            assertEquals(1, fired, "本次入口应恰好注册一份启动监听器");
        }

        @Override
        public void close() {
            try {
                FabricTestSupport.invoke(
                        bootstrap, "onServerStopped", new Class<?>[] {MinecraftServer.class}, server);
            } finally {
                if (loaderStub != null) {
                    loaderStub.close();
                }
                PlatformProvider.deactivate();
            }
        }
    }

    /** 连接句柄断言辅助：把不透明句柄还原为可断言的玩家信息。 */
    private static final class FabricConnectionHandleProbe {

        private final top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle connection;

        FabricConnectionHandleProbe(top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle connection) {
            this.connection = connection;
        }

        top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle connection() {
            return connection;
        }

        ServerPlayer player() {
            return ((top.wcpe.mc.mpmt.platform.fabric.net.FabricConnectionHandle) connection).player();
        }
    }
}
