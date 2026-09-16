package top.wcpe.mc.mpmt.platform.forge.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeConnectionHandle;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeServerTransport;

/**
 * Forge 26.2 产品入口的启停闭环：装配 → 玩家进出 → 停服清理。
 *
 * <p>事件监听器为私有，按仓库既有约定用反射驱动；事件对象本身是记录 / 可构造类型，构造真实实例。
 */
class MpmtForge262ModTest {

    @Test
    @DisplayName("构造即就绪：产品通道标识固定为 mpmt:main 且单例可复用")
    void 构造即暴露产品通道() {
        MpmtForge262Mod mod = new MpmtForge262Mod();

        assertNotNull(mod);
        assertEquals(Identifier.fromNamespaceAndPath("mpmt", "main"), MpmtForge262Mod.PRODUCT_CHANNEL);
        assertSame(MpmtForge262Mod.productChannel(), MpmtForge262Mod.productChannel());
        assertNotNull(MpmtForge262Mod.version());
    }

    @Test
    @DisplayName("服务端启动装配产品闭环，运行时进入启用态")
    void 服务端启动装配闭环() {
        MpmtForge262Mod mod = new MpmtForge262Mod();
        MinecraftServer server = ForgeTestSupport.newServer();

        try {
            call(mod, "onServerStarted", new Class<?>[] {ServerStartedEvent.class},
                    new ServerStartedEvent(server));

            assertNotNull(MpmtForge262Mod.serverNetworkFeature());
            MpmtRuntime runtime = activeRuntime();
            assertNotNull(runtime);
            assertEquals(MpmtRuntime.Phase.ENABLED, runtime.phase());
        } finally {
            call(mod, "onServerStopped", new Class<?>[] {ServerStoppedEvent.class},
                    new ServerStoppedEvent(server));
        }
    }

    @Test
    @DisplayName("服务端未启动时读取网络特性明确失败")
    void 未启动读取网络特性失败() {
        MpmtForge262Mod mod = new MpmtForge262Mod();
        call(mod, "onServerStopped", new Class<?>[] {ServerStoppedEvent.class},
                new ServerStoppedEvent(ForgeTestSupport.newServer()));

        assertThrows(IllegalStateException.class, MpmtForge262Mod::serverNetworkFeature);
    }

    @Test
    @DisplayName("玩家登录登记连接，登出后同一句柄被清除")
    void 玩家进出维护连接表() {
        MpmtForge262Mod mod = new MpmtForge262Mod();
        MinecraftServer server = ForgeTestSupport.newServer();
        ServerPlayer player = ForgeTestSupport.newPlayer(UUID.randomUUID(), "登录玩家");
        ForgeServerTransport transport = transportOf(mod);

        try {
            call(mod, "onServerStarted", new Class<?>[] {ServerStartedEvent.class},
                    new ServerStartedEvent(server));
            call(mod, "onPlayerLoggedIn",
                    new Class<?>[] {PlayerEvent.PlayerLoggedInEvent.class},
                    new PlayerEvent.PlayerLoggedInEvent(player));

            ForgeConnectionHandle registered = transport.onDisconnected(player);
            assertNotNull(registered, "登录后应登记连接句柄");

            transport.onConnected(player);
            call(mod, "onPlayerLoggedOut",
                    new Class<?>[] {PlayerEvent.PlayerLoggedOutEvent.class},
                    new PlayerEvent.PlayerLoggedOutEvent(player));

            assertNull(transport.onDisconnected(player), "登出后连接句柄应被清除");
        } finally {
            call(mod, "onServerStopped", new Class<?>[] {ServerStoppedEvent.class},
                    new ServerStoppedEvent(server));
        }
    }

    @Test
    @DisplayName("闭环未装配时玩家事件静默跳过，不登记连接也不抛异常")
    void 未装配时玩家事件静默跳过() {
        MpmtForge262Mod mod = new MpmtForge262Mod();
        ServerPlayer player = ForgeTestSupport.newPlayer(UUID.randomUUID(), "早到玩家");
        ForgeServerTransport transport = transportOf(mod);

        call(mod, "onPlayerLoggedIn", new Class<?>[] {PlayerEvent.PlayerLoggedInEvent.class},
                new PlayerEvent.PlayerLoggedInEvent(player));
        call(mod, "onPlayerLoggedOut", new Class<?>[] {PlayerEvent.PlayerLoggedOutEvent.class},
                new PlayerEvent.PlayerLoggedOutEvent(player));

        assertNull(activeRuntime());
        assertNull(transport.onDisconnected(player));
    }

    @Test
    @DisplayName("非服务端玩家实体（如假玩家）被静默忽略")
    void 非服务端玩家实体被忽略() {
        MpmtForge262Mod mod = new MpmtForge262Mod();
        MinecraftServer server = ForgeTestSupport.newServer();

        try {
            call(mod, "onServerStarted", new Class<?>[] {ServerStartedEvent.class},
                    new ServerStartedEvent(server));
            call(mod, "onPlayerLoggedIn", new Class<?>[] {PlayerEvent.PlayerLoggedInEvent.class},
                    new PlayerEvent.PlayerLoggedInEvent(null));
            call(mod, "onPlayerLoggedOut", new Class<?>[] {PlayerEvent.PlayerLoggedOutEvent.class},
                    new PlayerEvent.PlayerLoggedOutEvent(null));

            assertNotNull(MpmtForge262Mod.serverNetworkFeature());
        } finally {
            call(mod, "onServerStopped", new Class<?>[] {ServerStoppedEvent.class},
                    new ServerStoppedEvent(server));
        }
    }

    @Test
    @DisplayName("停服清理运行时、连接与网络特性")
    void 停服清理全部状态() {
        MpmtForge262Mod mod = new MpmtForge262Mod();
        MinecraftServer server = ForgeTestSupport.newServer();
        ServerPlayer player = ForgeTestSupport.newPlayer(UUID.randomUUID(), "清理玩家");
        ForgeServerTransport transport = transportOf(mod);

        call(mod, "onServerStarted", new Class<?>[] {ServerStartedEvent.class},
                new ServerStartedEvent(server));
        call(mod, "onPlayerLoggedIn", new Class<?>[] {PlayerEvent.PlayerLoggedInEvent.class},
                new PlayerEvent.PlayerLoggedInEvent(player));
        call(mod, "onServerStopped", new Class<?>[] {ServerStoppedEvent.class},
                new ServerStoppedEvent(server));

        assertNull(activeRuntime());
        assertNull(transport.onDisconnected(player));
        assertThrows(IllegalStateException.class, MpmtForge262Mod::serverNetworkFeature);
    }

    @Test
    @DisplayName("重复停服幂等：未装配时清理不抛异常")
    void 重复停服幂等() {
        MpmtForge262Mod mod = new MpmtForge262Mod();
        MinecraftServer server = ForgeTestSupport.newServer();

        call(mod, "onServerStarted", new Class<?>[] {ServerStartedEvent.class},
                new ServerStartedEvent(server));
        call(mod, "onServerStopped", new Class<?>[] {ServerStoppedEvent.class},
                new ServerStoppedEvent(server));
        call(mod, "onServerStopped", new Class<?>[] {ServerStoppedEvent.class},
                new ServerStoppedEvent(server));

        assertNull(activeRuntime());
    }

    private static void call(MpmtForge262Mod mod, String name, Class<?>[] parameterTypes, Object argument) {
        ForgeTestSupport.invoke(mod, name, parameterTypes, argument);
    }

    private static ForgeServerTransport transportOf(MpmtForge262Mod mod) {
        return (ForgeServerTransport) ForgeTestSupport.getField(
                mod, ForgeTestSupport.field(MpmtForge262Mod.class, "transport"));
    }

    @SuppressWarnings("unchecked")
    private static MpmtRuntime activeRuntime() {
        try {
            Field field = MpmtForge262Mod.class.getDeclaredField("activeRuntime");
            field.setAccessible(true);
            return (MpmtRuntime) field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("读取 activeRuntime 失败", error);
        }
    }
}
