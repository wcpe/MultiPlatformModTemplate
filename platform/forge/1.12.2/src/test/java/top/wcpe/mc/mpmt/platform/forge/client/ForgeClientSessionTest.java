package top.wcpe.mc.mpmt.platform.forge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.client.ClientNetworkFeature;
import top.wcpe.mc.mpmt.platform.forge.ForgeTestSupport;
import top.wcpe.mc.mpmt.platform.forge.hud.ForgeHudSnapshot;

/**
 * {@link ForgeClientSession} 的每次连接独立会话生命周期：join 建栈、disconnect 拆栈、快照透传。
 *
 * <p>会话只依赖我方 L0/L1 接缝（传输端口 / HUD 接缝 / 机器码提供者），故用假实现注入即可在纯 JVM 下驱动
 * 真实的 {@code MpmtRuntime} + {@code ClientNetworkFeature} 装配链。
 */
class ForgeClientSessionTest {

    @Test
    @DisplayName("构造：传输 / HUD / 版本 / 机器码提供者任一为空即拒绝")
    void 构造拒绝空依赖() {
        ForgeTestSupport.FakeClientTransport transport = new ForgeTestSupport.FakeClientTransport();
        ForgeTestSupport.FakeHud hud = new ForgeTestSupport.FakeHud();
        ForgeTestSupport.FakeMachineCodeProvider provider = new ForgeTestSupport.FakeMachineCodeProvider("甲");

        assertThrows(
                NullPointerException.class,
                () -> new ForgeClientSession(null, hud, "1.0", provider));
        assertThrows(
                NullPointerException.class,
                () -> new ForgeClientSession(transport, null, "1.0", provider));
        assertThrows(
                NullPointerException.class,
                () -> new ForgeClientSession(transport, hud, null, provider));
        assertThrows(
                NullPointerException.class,
                () -> new ForgeClientSession(transport, hud, "1.0", null));
    }

    @Test
    @DisplayName("初始态：未 join 时网络特性为空、HUD 快照为空")
    void 初始态未装配() {
        ForgeClientSession session = newSession(new ForgeTestSupport.FakeClientTransport(), new ForgeTestSupport.FakeHud());

        assertNull(session.networkFeature(), "未 join 不应有网络特性");
        assertNull(session.hudSnapshot(), "未 join 不应有 HUD 快照");
    }

    @Test
    @DisplayName("join：装配运行时、注册传输端口、启动握手、把派发器交给 HUD")
    void join装配并启动握手() {
        ForgeTestSupport.FakeClientTransport transport = new ForgeTestSupport.FakeClientTransport();
        ForgeTestSupport.FakeHud hud = new ForgeTestSupport.FakeHud();
        ForgeClientSession session = newSession(transport, hud);

        session.join();

        assertNotNull(session.networkFeature(), "join 后应暴露网络特性");
        assertTrue(transport.isReceiverRegistered(), "传输端口应已注册收包回调");
        assertTrue(hud.isRegistered(), "HUD 接缝应已注册包派发器");
        assertNotNull(hud.dispatcher(), "派发器应非空");
        assertTrue(transport.sent.size() >= 1, "启动握手应向服务端发出至少一个包");
        assertEquals(1, transport.clearCount, "join 应先拆旧会话（清空收包器）再装配");
    }

    @Test
    @DisplayName("join：两次 join 幂等重建，会话实例被替换而非叠加")
    void join可重复调用() {
        ForgeTestSupport.FakeClientTransport transport = new ForgeTestSupport.FakeClientTransport();
        ForgeTestSupport.FakeHud hud = new ForgeTestSupport.FakeHud();
        ForgeClientSession session = newSession(transport, hud);

        session.join();
        ClientNetworkFeature first = session.networkFeature();
        session.join();

        assertNotSame(first, session.networkFeature(), "每次 join 应重建网络特性");
        assertEquals(2, hud.registerCount, "每次 join 都应重新注册 HUD 派发器");
        assertTrue(transport.clearCount >= 1, "join 应先拆旧会话（清空收包器）");
    }

    @Test
    @DisplayName("disconnect：拆栈清空收包器与 HUD 快照，并复位网络特性")
    void disconnect拆栈() {
        ForgeTestSupport.FakeClientTransport transport = new ForgeTestSupport.FakeClientTransport();
        ForgeTestSupport.FakeHud hud = new ForgeTestSupport.FakeHud();
        ForgeClientSession session = newSession(transport, hud);
        session.join();
        hud.设snapshot(
                new ForgeHudSnapshot(
                        top.wcpe.mc.mpmt.protocol.packet.HudKind.CHAT, "残留", "", 1L));

        session.disconnect();

        assertNull(session.networkFeature(), "断线后网络特性应复位");
        assertNull(session.hudSnapshot(), "断线后 HUD 快照应被清除");
        // join 内部先拆一次旧会话，故 disconnect 累计 2 次
        assertEquals(2, transport.clearCount, "断线应清空收包器");
        assertEquals(2, hud.clearCount, "断线应清空 HUD");
        assertTrue(!transport.isReceiverRegistered(), "收包器应已被摘除");
    }

    @Test
    @DisplayName("disconnect：未 join 也可安全调用（幂等释放）")
    void disconnect未装配时安全() {
        ForgeTestSupport.FakeClientTransport transport = new ForgeTestSupport.FakeClientTransport();
        ForgeTestSupport.FakeHud hud = new ForgeTestSupport.FakeHud();
        ForgeClientSession session = newSession(transport, hud);

        session.disconnect();

        assertNull(session.networkFeature());
        assertEquals(1, transport.clearCount, "仍应清空收包器");
        assertEquals(1, hud.clearCount);
    }

    @Test
    @DisplayName("disconnect：重复调用多次仍安全，不抛异常")
    void disconnect可重复调用() {
        ForgeTestSupport.FakeClientTransport transport = new ForgeTestSupport.FakeClientTransport();
        ForgeTestSupport.FakeHud hud = new ForgeTestSupport.FakeHud();
        ForgeClientSession session = newSession(transport, hud);
        session.join();

        session.disconnect();
        session.disconnect();

        assertNull(session.networkFeature());
        assertEquals(3, transport.clearCount, "join 一次 + disconnect 两次");
        assertEquals(3, hud.clearCount);
    }

    @Test
    @DisplayName("HUD 快照：透传接缝的当前快照，会话不自行缓存")
    void HUD快照透传接缝() {
        ForgeTestSupport.FakeClientTransport transport = new ForgeTestSupport.FakeClientTransport();
        ForgeTestSupport.FakeHud hud = new ForgeTestSupport.FakeHud();
        ForgeClientSession session = newSession(transport, hud);
        session.join();

        ForgeHudSnapshot expected =
                new ForgeHudSnapshot(
                        top.wcpe.mc.mpmt.protocol.packet.HudKind.TITLE, "主", "副", 2000L);
        hud.设snapshot(expected);

        assertEquals(expected, session.hudSnapshot(), "会话应直接透传接缝快照");
        assertEquals("主", session.hudSnapshot().text());
    }

    @Test
    @DisplayName("断线重连：清空后再次 join 得到全新可用会话")
    void 断线重连后可用() {
        ForgeTestSupport.FakeClientTransport transport = new ForgeTestSupport.FakeClientTransport();
        ForgeTestSupport.FakeHud hud = new ForgeTestSupport.FakeHud();
        ForgeClientSession session = newSession(transport, hud);
        session.join();
        session.disconnect();

        session.join();

        assertNotNull(session.networkFeature(), "重连后应重新装配网络特性");
        assertNotNull(hud.dispatcher(), "重连后 HUD 应重新拿到派发器");
        assertTrue(transport.isReceiverRegistered(), "重连后传输应重新注册收包回调");
    }

    private static ForgeClientSession newSession(
            ForgeTestSupport.FakeClientTransport transport, ForgeTestSupport.FakeHud hud) {
        return ForgeTestSupport.session(transport, hud);
    }

}
