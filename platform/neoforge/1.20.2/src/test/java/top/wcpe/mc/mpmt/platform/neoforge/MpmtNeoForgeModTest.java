package top.wcpe.mc.mpmt.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeConnectionHandle;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeServerTransport;
import top.wcpe.mc.mpmt.platform.neoforge.version.NeoForgeServerNetwork;
import top.wcpe.mc.mpmt.platform.neoforge.version.SupportedVersion;
import top.wcpe.mc.mpmt.platform.neoforge.version.v1_20_2.V1_20_2ServerNetwork;

/**
 * NeoForge mod 入口：版本探测、启停闭环与玩家进出。
 *
 * <p>事件监听器是公开方法，可直接调用；服务端与玩家用替身注入。静态传输与闭环在用例间必须还原。
 */
class MpmtNeoForgeModTest {

    private NeoForgeServerTransport previousTransport;

    /** 每个用例前重置网络注册表与版本元数据，使 mod 主类可重复构造。 */
    @BeforeEach
    void 准备装载器状态() {
        NeoForgeTestSupport.resetNetworkRegistry();
        NeoForgeTestSupport.installFmlVersionInfo("1.20.2");
        previousTransport = activeTransport();
    }

    /** 用例结束后还原进程级静态状态，避免污染其它测试类。 */
    @AfterEach
    void 还原静态状态() {
        setActiveTransport(previousTransport);
        NeoForgeTestSupport.resetNetworkRegistry();
    }

    @Test
    @DisplayName("探测源返回锚点版本时选中 v1_20_2 适配器")
    void 探测选中锚点适配器() {
        NeoForgeServerNetwork network = MpmtNeoForgeMod.detectServerNetwork(() -> "1.20.2");

        assertNotNull(network);
        assertTrue(network instanceof V1_20_2ServerNetwork,
                "1.20.2 应选中 v1_20_2 适配器，实际为 " + network.getClass());
        assertEquals(1_048_576, network.maxPayloadSize());
    }

    @Test
    @DisplayName("未知版本探测明确失败而非静默退化")
    void 未知版本失败快() {
        assertThrows(IllegalStateException.class,
                () -> MpmtNeoForgeMod.detectServerNetwork(() -> "1.20.1"));
    }

    @Test
    @DisplayName("空探测源被拒绝")
    void 空探测源被拒绝() {
        assertThrows(NullPointerException.class, () -> MpmtNeoForgeMod.detectServerNetwork(null));
    }

    @Test
    @DisplayName("服务端启动装配闭环，运行时进入启用态")
    void 服务端启动装配闭环() {
        MpmtNeoForgeMod mod = new MpmtNeoForgeMod();
        MinecraftServer server = NeoForgeTestSupport.newServer();
        installTransport(mod, new V1_20_2ServerNetwork("mpmt", "mod-start"));

        try {
            mod.onServerStarted(new ServerStartedEvent(server));

            assertNotNull(MpmtNeoForgeMod.serverNetworkFeature());
            assertNotNull(runtimeOf(mod));
            assertEquals(MpmtRuntime.Phase.ENABLED, runtimeOf(mod).phase());
        } finally {
            mod.onServerStopped(new ServerStoppedEvent(server));
        }
    }

    @Test
    @DisplayName("服务端停止释放运行时、连接与进程级平台绑定")
    void 服务端停止释放状态() {
        MpmtNeoForgeMod mod = new MpmtNeoForgeMod();
        MinecraftServer server = NeoForgeTestSupport.newServer();
        ServerPlayer player = NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "停服玩家");

        try {
            mod.onServerStarted(new ServerStartedEvent(server));
            mod.onPlayerLoggedIn(new PlayerEvent.PlayerLoggedInEvent(player));

            mod.onServerStopped(new ServerStoppedEvent(server));

            assertNull(runtimeOf(mod));
            assertNull(servicesOf(mod));
            assertThrows(IllegalStateException.class, MpmtNeoForgeMod::serverNetworkFeature);
        } finally {
            mod.onServerStopped(new ServerStoppedEvent(server));
        }
    }

    @Test
    @DisplayName("玩家登录登记唯一连接，登出后连接被清除")
    void 玩家进出维护连接() {
        MpmtNeoForgeMod mod = new MpmtNeoForgeMod();
        MinecraftServer server = NeoForgeTestSupport.newServer();
        ServerPlayer player = NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "进出玩家");
        NeoForgeServerTransport transport = transportOf(mod);

        try {
            mod.onServerStarted(new ServerStartedEvent(server));
            mod.onPlayerLoggedIn(new PlayerEvent.PlayerLoggedInEvent(player));

            NeoForgeConnectionHandle handle = transport.connectionFor(player);
            assertNotNull(handle);
            assertSame(handle, transport.connectionFor(player), "同一玩家应复用句柄");

            mod.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            assertNull(transport.onDisconnected(player), "登出后连接应被清除");
        } finally {
            mod.onServerStopped(new ServerStoppedEvent(server));
        }
    }

    @Test
    @DisplayName("闭环未装配时玩家事件静默跳过")
    void 未装配时玩家事件跳过() {
        MpmtNeoForgeMod mod = new MpmtNeoForgeMod();
        ServerPlayer player = NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "早到玩家");

        mod.onPlayerLoggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
        mod.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));

        assertNull(servicesOf(mod));
    }

    @Test
    @DisplayName("非服务端玩家实体被静默忽略")
    void 非服务端玩家被忽略() {
        MpmtNeoForgeMod mod = new MpmtNeoForgeMod();
        MinecraftServer server = NeoForgeTestSupport.newServer();

        try {
            mod.onServerStarted(new ServerStartedEvent(server));
            mod.onPlayerLoggedIn(new PlayerEvent.PlayerLoggedInEvent(null));
            mod.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(null));

            assertNotNull(servicesOf(mod));
        } finally {
            mod.onServerStopped(new ServerStoppedEvent(server));
        }
    }

    @Test
    @DisplayName("重复停服幂等，不抛异常")
    void 重复停服幂等() {
        MpmtNeoForgeMod mod = new MpmtNeoForgeMod();
        MinecraftServer server = NeoForgeTestSupport.newServer();

        mod.onServerStopped(new ServerStoppedEvent(server));
        mod.onServerStopped(new ServerStoppedEvent(server));

        assertNull(runtimeOf(mod));
    }

    @Test
    @DisplayName("服务端传输未启用时读取传输与网络特性明确失败")
    void 传输未启用时失败() {
        MpmtNeoForgeMod mod = new MpmtNeoForgeMod();
        NeoForgeServerTransport previous = activeTransport();

        try {
            setActiveTransport(null);
            assertThrows(IllegalStateException.class, MpmtNeoForgeMod::serverTransport);
            assertThrows(IllegalStateException.class, MpmtNeoForgeMod::serverNetworkFeature);
            assertThrows(IllegalStateException.class,
                    () -> MpmtNeoForgeMod.sendActive(NeoForgeTestSupport.newPlayer(
                            UUID.randomUUID(), "无传输"), new byte[] {1}));
        } finally {
            setActiveTransport(previous);
            assertNotNull(mod);
        }
    }

    @Test
    @DisplayName("跨端发送经活跃传输的玩家连接投递")
    void 跨端发送经活跃传输() {
        MpmtNeoForgeMod mod = new MpmtNeoForgeMod();
        MinecraftServer server = NeoForgeTestSupport.newServer();
        ServerPlayer player = NeoForgeTestSupport.newPlayer(UUID.randomUUID(), "收包玩家");

        try {
            mod.onServerStarted(new ServerStartedEvent(server));
            MpmtNeoForgeMod.sendActive(player, new byte[] {1, 2, 3});

            assertNotNull(transportOf(mod).connectionFor(player));
        } finally {
            mod.onServerStopped(new ServerStoppedEvent(server));
        }
    }

    @Test
    @DisplayName("当前锚点报告的版本号与枚举一致")
    void 锚点版本一致() {
        assertEquals("1.20.2", SupportedVersion.V1_20_2.mcVersion());
    }

    private static void installTransport(MpmtNeoForgeMod mod, NeoForgeServerNetwork network) {
        NeoForgeTestSupport.setField(mod,
                NeoForgeTestSupport.field(MpmtNeoForgeMod.class, "transport"),
                new NeoForgeServerTransport(network));
    }

    private static NeoForgeServerTransport transportOf(MpmtNeoForgeMod mod) {
        return (NeoForgeServerTransport) NeoForgeTestSupport.getField(mod,
                NeoForgeTestSupport.field(MpmtNeoForgeMod.class, "transport"));
    }

    private static MpmtRuntime runtimeOf(MpmtNeoForgeMod mod) {
        return (MpmtRuntime) NeoForgeTestSupport.getField(mod,
                NeoForgeTestSupport.field(MpmtNeoForgeMod.class, "runtime"));
    }

    private static Object servicesOf(MpmtNeoForgeMod mod) {
        return NeoForgeTestSupport.getField(mod,
                NeoForgeTestSupport.field(MpmtNeoForgeMod.class, "services"));
    }

    private static NeoForgeServerTransport activeTransport() {
        return (NeoForgeServerTransport) NeoForgeTestSupport.getStatic(
                MpmtNeoForgeMod.class, "activeTransport");
    }

    private static void setActiveTransport(NeoForgeServerTransport transport) {
        NeoForgeTestSupport.setStatic(MpmtNeoForgeMod.class, "activeTransport", transport);
    }

}
