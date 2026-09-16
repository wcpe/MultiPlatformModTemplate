package top.wcpe.mc.mpmt.platform.forge.modern.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.forge.modern.Forge121MinecraftTestSupport;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;

/** Forge 1.21.1 客户端连接桥接：会话单例与登出清理。 */
class ForgeClientEventsTest {

    @BeforeAll
    static void 预置运行期替身() {
        Forge121MinecraftTestSupport.bootstrapMinecraft();
        Forge121MinecraftTestSupport.primeEventListeners(
                net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn.class,
                net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class,
                net.minecraftforge.event.network.CustomPayloadEvent.class);
        Forge121MinecraftTestSupport.installEmptyModList();
        Forge121MinecraftTestSupport.resetChannelRegistry();
    }

    @Test
    @DisplayName("会话为单例，未连接时网络特性与 HUD 快照均为空")
    void 会话单例与初始状态() {
        ForgeClientSession session = ForgeClientEvents.session();

        assertNotNull(session);
        assertSame(session, ForgeClientEvents.session());
        assertNull(session.networkFeature());
        assertNull(session.hudSnapshot());
    }

    @Test
    @DisplayName("登出清理在未连接时幂等，不抛异常")
    void 未连接登出幂等() {
        ForgeClientEvents.onLoggingOut(null);
        ForgeClientEvents.onLoggingOut(null);

        assertNull(ForgeClientEvents.session().networkFeature());
        assertNull(ForgeClientEvents.session().hudSnapshot());
    }

    @Test
    @DisplayName("会话断线后可再次清理，状态保持一致")
    void 断线清理状态一致() {
        ForgeClientEvents.session().disconnect();

        assertNull(ForgeClientEvents.session().networkFeature());
        assertSame(ForgeClientEvents.session(), ForgeClientEvents.session());
    }

    @Test
    @DisplayName("无通道的测试会话明确拒绝真实网络连接")
    void 测试会话拒绝网络连接() throws Exception {
        java.lang.reflect.Constructor<ForgeClientSession> constructor =
                ForgeClientSession.class.getDeclaredConstructor(
                        ForgeClientHud.class, String.class,
                        top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider.class);
        constructor.setAccessible(true);
        ForgeClientSession session = constructor.newInstance(
                new ForgeClientHud(), "v", (top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider) () -> "c");

        assertThrows(IllegalStateException.class,
                () -> session.join(Forge121MinecraftTestSupport.newClientConnection()));
        assertNull(session.networkFeature());
    }

    @Test
    @DisplayName("HUD 快照为不可变记录，携带四类字段")
    void HUD快照记录() {
        ForgeHudSnapshot snapshot = new ForgeHudSnapshot(HudKind.TITLE, "标题", "副标题", 1000L);

        assertEquals(HudKind.TITLE, snapshot.kind());
        assertEquals("标题", snapshot.text());
        assertEquals("副标题", snapshot.subtitle());
        assertEquals(1000L, snapshot.durationMillis());
        assertTrue(ForgeHudSnapshot.class.isRecord());
        assertFalse(snapshot.toString().isEmpty());
    }
}
