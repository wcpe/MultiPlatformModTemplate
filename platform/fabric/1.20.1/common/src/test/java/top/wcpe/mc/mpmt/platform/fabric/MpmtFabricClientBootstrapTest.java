package top.wcpe.mc.mpmt.platform.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.fabric.FabricTestSupport.LoaderStub;
import top.wcpe.mc.mpmt.platform.fabric.client.FabricClientSession;

/**
 * Fabric 客户端入口：初始化注册连接 / tick 回调、会话装配与失败快语义。
 *
 * <p>入口依赖运行期 MC 版本元数据与 L4 绑定，测试经加载器替身提供版本串；
 * 回调经反射取回监听器后按真实签名触发。
 */
class MpmtFabricClientBootstrapTest {

    @BeforeEach
    @AfterEach
    void 重置会话() {
        // 静态会话跨用例互相影响：先断开既有会话再重置
        try {
            MpmtFabricClientBootstrap.session().disconnect();
        } catch (IllegalStateException ignored) {
            // 尚未初始化即无需清理
        }
        ((java.util.concurrent.atomic.AtomicReference<?>)
                        MinecraftTestSupport.staticField(MpmtFabricClientBootstrap.class, "SESSION"))
                .set(null);
    }

    @Test
    @DisplayName("初始化注册 play 连接 JOIN / DISCONNECT 与客户端 tick 回调")
    void 初始化注册回调() {
        Baseline baseline = new Baseline();

        runInitialize();

        assertEquals(baseline.joins + 1, listeners(ClientPlayConnectionEvents.JOIN));
        assertEquals(baseline.disconnects + 1, listeners(ClientPlayConnectionEvents.DISCONNECT));
        assertEquals(baseline.ticks + 1, listeners(ClientTickEvents.END_CLIENT_TICK));
    }

    @Test
    @DisplayName("初始化后即可取会话，且会话携带产品网络特性")
    void 初始化装配会话() {
        runInitialize();

        FabricClientSession session = MpmtFabricClientBootstrap.session();
        assertNotNull(session);
        assertNull(session.networkFeature(), "未 JOIN play 连接前不应有产品网络特性");
        assertThrows(
                IllegalStateException.class,
                MpmtFabricClientBootstrap::networkFeature,
                "未 JOIN 时取网络特性须失败快");
    }

    @Test
    @DisplayName("未初始化即取会话失败快，不返回残缺对象")
    void 未初始化失败快() {
        assertThrows(IllegalStateException.class, MpmtFabricClientBootstrap::session);
    }

    @Test
    @DisplayName("JOIN 后会话启用产品网络特性；断线后会话丢弃特性")
    void 连接生命周期驱动会话() {
        Baseline baseline = new Baseline();
        runInitialize();
        Minecraft client = ClientTestSupport.newClient();

        joinHandlers(baseline).forEach(handler -> handler.onPlayReady(null, null, client));
        assertNotNull(MpmtFabricClientBootstrap.networkFeature(), "JOIN 后应装配产品网络特性");

        disconnectHandlers(baseline).forEach(handler -> handler.onPlayDisconnect(null, client));
        assertNull(
                MpmtFabricClientBootstrap.session().networkFeature(), "断线后须丢弃产品网络特性");
    }

    @Test
    @DisplayName("tick 回调在玩家与连接未就绪时不发起握手")
    void tick未就绪不握手() {
        Baseline baseline = new Baseline();
        runInitialize();
        Minecraft client = ClientTestSupport.newClient();

        endTickHandlers(baseline).forEach(handler -> handler.onEndTick(client));

        assertNull(
                MpmtFabricClientBootstrap.session().networkFeature(),
                "未 JOIN 时 tick 不应装配任何网络特性");
    }

    @Test
    @DisplayName("JOIN 后玩家未就绪的 tick 不推进握手，且不抛错")
    void JOIN后tick等待就绪() {
        Baseline baseline = new Baseline();
        runInitialize();
        Minecraft client = ClientTestSupport.newClient();

        joinHandlers(baseline).forEach(handler -> handler.onPlayReady(null, null, client));
        endTickHandlers(baseline).forEach(handler -> handler.onEndTick(client));

        assertNotNull(MpmtFabricClientBootstrap.networkFeature(), "会话仍应保持启用");
    }

    private static void runInitialize() {
        try (LoaderStub loader =
                FabricTestSupport.stubMinecraftVersion(System.getProperty("mpmt.test.minecraftVersion"))) {
            assertEquals(
                    loader.stubbedVersion(),
                    top.wcpe.mc.mpmt.platform.fabric.version.FabricVersions.actualMinecraftVersion());
            new MpmtFabricClientBootstrap().onInitializeClient();
        }
    }

    private static int listeners(net.fabricmc.fabric.api.event.Event<?> event) {
        return FabricTestSupport.listenersOf(event, Object.class).size();
    }

    private static java.util.List<ClientPlayConnectionEvents.Join> joinHandlers(Baseline baseline) {
        return FabricTestSupport.listenersFrom(
                ClientPlayConnectionEvents.JOIN, ClientPlayConnectionEvents.Join.class, baseline.joins);
    }

    private static java.util.List<ClientPlayConnectionEvents.Disconnect> disconnectHandlers(
            Baseline baseline) {
        return FabricTestSupport.listenersFrom(
                ClientPlayConnectionEvents.DISCONNECT,
                ClientPlayConnectionEvents.Disconnect.class,
                baseline.disconnects);
    }

    private static java.util.List<ClientTickEvents.EndTick> endTickHandlers(Baseline baseline) {
        return FabricTestSupport.listenersFrom(
                ClientTickEvents.END_CLIENT_TICK,
                ClientTickEvents.EndTick.class,
                baseline.ticks);
    }

    /** 注册前的监听器基线：排除本进程内先前用例留下的监听器。 */
    private static final class Baseline {

        private final int joins = listeners(ClientPlayConnectionEvents.JOIN);
        private final int disconnects = listeners(ClientPlayConnectionEvents.DISCONNECT);
        private final int ticks = listeners(ClientTickEvents.END_CLIENT_TICK);
    }
}
