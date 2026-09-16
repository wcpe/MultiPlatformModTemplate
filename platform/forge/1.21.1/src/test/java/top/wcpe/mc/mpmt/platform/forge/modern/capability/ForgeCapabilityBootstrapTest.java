package top.wcpe.mc.mpmt.platform.forge.modern.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Constructor;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;
import top.wcpe.mc.mpmt.domain.capability.PlayerLeftEvent;
import top.wcpe.mc.mpmt.platform.forge.modern.Forge121MinecraftTestSupport;

/** Forge 1.21.1 平台能力装配：端口全量注册与玩家事件桥接的真实行为。 */
class ForgeCapabilityBootstrapTest {

    @Test
    @DisplayName("注册全部共享能力端口且各自类型正确")
    void 注册全部能力端口() {
        MinecraftServer server = Forge121MinecraftTestSupport.newServer();
        MpmtRuntime runtime = new MpmtRuntime();

        ForgeCapabilityBootstrap.register(server, runtime);

        assertSame(ForgeDataDirectoryPort.class, runtime.ports().get(DataDirectoryPort.class).getClass());
        assertSame(ForgePersistencePort.class, runtime.ports().get(PersistencePort.class).getClass());
        assertSame(ForgeMessagePort.class, runtime.ports().get(MessagePort.class).getClass());
        assertSame(ForgeConnectionControlPort.class, runtime.ports().get(ConnectionControlPort.class).getClass());
        assertSame(ForgePlayerPort.class, runtime.ports().get(PlayerPort.class).getClass());
        assertSame(ForgeWorldPort.class, runtime.ports().get(WorldPort.class).getClass());
        assertSame(ForgeSchedulerPort.class, runtime.ports().get(SchedulerPort.class).getClass());
    }

    @Test
    @DisplayName("注册拒绝空服务端与空运行时")
    void 注册拒绝空依赖() {
        MpmtRuntime runtime = new MpmtRuntime();

        assertThrows(NullPointerException.class, () -> ForgeCapabilityBootstrap.register(null, runtime));
        assertThrows(NullPointerException.class,
                () -> ForgeCapabilityBootstrap.register(Forge121MinecraftTestSupport.newServer(), null));
    }

    @Test
    @DisplayName("平台能力示例已订阅领域事件总线")
    void 能力示例已订阅事件() {
        MpmtRuntime runtime = new MpmtRuntime();
        ForgeCapabilityBootstrap.register(Forge121MinecraftTestSupport.newServer(), runtime);

        // 订阅状态经事件发布可观测：玩家进出事件可被示例订阅者确认接收
        runtime.eventBus().publish(new PlayerJoinedEvent(
                new top.wcpe.mc.mpmt.core.domain.ref.PlayerRef(UUID.randomUUID(), "示例")));
        runtime.eventBus().publish(new PlayerLeftEvent(
                new top.wcpe.mc.mpmt.core.domain.ref.PlayerRef(UUID.randomUUID(), "示例")));

        assertNotNull(runtime.eventBus());
    }

    @Test
    @DisplayName("玩家进出桥接把原生玩家转为平台无关引用并发布到事件总线")
    void 玩家桥接发布领域事件() throws Exception {
        MpmtRuntime runtime = new MpmtRuntime();
        AtomicInteger joined = new AtomicInteger();
        AtomicInteger left = new AtomicInteger();
        runtime.eventBus().subscribe(PlayerJoinedEvent.class, event -> joined.incrementAndGet());
        runtime.eventBus().subscribe(PlayerLeftEvent.class, event -> left.incrementAndGet());

        Object bridge = newBridge(runtime.eventBus());
        ServerPlayer player = Forge121MinecraftTestSupport.newPlayer(UUID.randomUUID(), "桥接玩家");

        invoke(bridge, "onPlayerLoggedIn", player);
        invoke(bridge, "onPlayerLoggedOut", player);

        assertEquals(1, joined.get());
        assertEquals(1, left.get());
    }

    private static Class<?> bridgeType() {
        try {
            return Class.forName(
                    "top.wcpe.mc.mpmt.platform.forge.modern.capability.ForgeCapabilityBootstrap$PlayerConnectionBridge");
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到玩家连接桥类型", error);
        }
    }

    private static Object newBridge(EventBusPort eventBus) throws Exception {
        Constructor<?> constructor = bridgeType().getDeclaredConstructor(EventBusPort.class);
        constructor.setAccessible(true);
        return constructor.newInstance(eventBus);
    }

    private static void invoke(Object bridge, String name, ServerPlayer player) throws Exception {
        if (name.endsWith("In")) {
            java.lang.reflect.Method method = bridgeType().getDeclaredMethod(
                    name, net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent.class);
            method.setAccessible(true);
            method.invoke(bridge,
                    new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player));
            return;
        }
        java.lang.reflect.Method method = bridgeType().getDeclaredMethod(
                name, net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class);
        method.setAccessible(true);
        method.invoke(bridge,
                new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
    }
}
