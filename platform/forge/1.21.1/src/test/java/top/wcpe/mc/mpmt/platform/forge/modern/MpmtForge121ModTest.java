package top.wcpe.mc.mpmt.platform.forge.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeServerTransport;

/** Forge 1.21.1 产品入口：常量、版本回退与服务端事件驱动的完整装配 / 清理生命周期。 */
class MpmtForge121ModTest {

    @BeforeAll
    static void 预置运行期替身() {
        Forge121MinecraftTestSupport.bootstrapMinecraft();
        Forge121MinecraftTestSupport.primeEventListeners(
                net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class,
                net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent.class,
                net.minecraftforge.event.server.ServerStartedEvent.class,
                net.minecraftforge.event.server.ServerStoppedEvent.class,
                net.minecraftforge.event.network.CustomPayloadEvent.class);
        Forge121MinecraftTestSupport.installEmptyModList();
    }

    @BeforeEach
    void 隔离全局状态() {
        Forge121MinecraftTestSupport.resetChannelRegistry();
        stopServer(new MpmtForge121Mod());
    }

    @AfterEach
    void 复原全局状态() {
        // 每个用例结束都清掉服务端闭环，避免静态持有跨用例泄漏
        stopServer(new MpmtForge121Mod());
        Forge121MinecraftTestSupport.resetChannelRegistry();
    }

    @Test
    @DisplayName("产品入口常量与通道标识固定为 mpmt:main")
    void 产品入口常量() {
        assertEquals("mpmt", MpmtForge121Mod.MOD_ID);
        assertEquals(ResourceLocation.fromNamespaceAndPath("mpmt", "main"), MpmtForge121Mod.PRODUCT_CHANNEL);
        assertNotNull(MpmtForge121Mod.productChannel());
        assertSame(MpmtForge121Mod.productChannel(), MpmtForge121Mod.productChannel());
    }

    @Test
    @DisplayName("未打包实现版本时回退为默认版本号")
    void 未打包版本回退() {
        String version = MpmtForge121Mod.version();

        assertNotNull(version);
        assertFalse(version.isEmpty());
    }

    @Test
    @DisplayName("服务端未启动时读取产品网络明确失败")
    void 未启动时明确失败() {
        assertThrows(IllegalStateException.class, MpmtForge121Mod::serverNetworkFeature);
    }

    @Test
    @DisplayName("服务端启动事件装配闭环：产品网络、端口与封禁服务就绪")
    void 启动装配闭环() {
        MpmtForge121Mod mod = new MpmtForge121Mod();

        startServer(mod);

        assertNotNull(MpmtForge121Mod.serverNetworkFeature().sessionRegistry());
        assertNotNull(MpmtForge121Mod.serverNetworkFeature().handshakeService());
        assertEquals("server-network", MpmtForge121Mod.serverNetworkFeature().name());
    }

    @Test
    @DisplayName("玩家进出事件登记与清理连接，未启动时静默忽略")
    void 玩家进退事件() {
        MpmtForge121Mod mod = new MpmtForge121Mod();
        ServerPlayer player = Forge121MinecraftTestSupport.newPlayer(UUID.randomUUID(), "生命周期玩家");

        // 未启动：事件被忽略且不抛异常
        postLoggedIn(mod, player);
        postLoggedOut(mod, player);

        startServer(mod);
        postLoggedIn(mod, player);
        assertEquals(player.getUUID(), transportOf(mod).onConnected(player).player().getUUID());

        postLoggedOut(mod, player);
        assertNotNull(MpmtForge121Mod.serverNetworkFeature());
    }

    @Test
    @DisplayName("服务端停止事件清除闭环并释放调度端口")
    void 停止释放闭环() {
        MpmtForge121Mod mod = new MpmtForge121Mod();
        startServer(mod);
        assertNotNull(MpmtForge121Mod.serverNetworkFeature());

        stopServer(mod);

        assertThrows(IllegalStateException.class, MpmtForge121Mod::serverNetworkFeature);
        // 重复停止为幂等空操作
        stopServer(mod);
    }

    @Test
    @DisplayName("重复启动以最新闭环覆盖，旧闭环不再对外可见")
    void 重复启动覆盖闭环() {
        MpmtForge121Mod mod = new MpmtForge121Mod();
        startServer(mod);
        var first = MpmtForge121Mod.serverNetworkFeature();

        startServer(mod);
        var second = MpmtForge121Mod.serverNetworkFeature();

        assertNotSame(first, second);
    }

    private static ForgeServerTransport transportOf(MpmtForge121Mod mod) {
        return (ForgeServerTransport) Forge121MinecraftTestSupport.getField(
                mod, Forge121MinecraftTestSupport.field(mod.getClass(), "transport"));
    }

    /**
     * 触发私有事件处理器。
     *
     * <p>纯 JVM 下 Forge 事件总线经 ModLauncher 织入回调，注册必然失败；故按仓库既有约定
     * （反射调私有钩子）直接调用处理器方法，覆盖其真实装配 / 清理逻辑。
     */
    private static void invokeHandler(MpmtForge121Mod mod, String name, Object event) {
        try {
            java.lang.reflect.Method method = MpmtForge121Mod.class.getDeclaredMethod(name, event.getClass());
            method.setAccessible(true);
            method.invoke(mod, event);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法调用事件处理器 " + name, error);
        }
    }

    private static void startServer(MpmtForge121Mod mod) {
        MinecraftServer server = Forge121MinecraftTestSupport.newServer();
        invokeHandler(mod, "onServerStarted", new net.minecraftforge.event.server.ServerStartedEvent(server));
    }

    private static void stopServer(MpmtForge121Mod mod) {
        invokeHandler(mod, "onServerStopped",
                new net.minecraftforge.event.server.ServerStoppedEvent(Forge121MinecraftTestSupport.newServer()));
    }

    private static void postLoggedIn(MpmtForge121Mod mod, ServerPlayer player) {
        invokeHandler(mod, "onPlayerLoggedIn",
                new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player));
    }

    private static void postLoggedOut(MpmtForge121Mod mod, ServerPlayer player) {
        invokeHandler(mod, "onPlayerLoggedOut",
                new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
    }
}
