package top.wcpe.mc.mpmt.platform.forge.modern.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.platform.forge.modern.Forge121MinecraftTestSupport;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** Forge 1.21.1 客户端 HUD：四类呈现均记录快照，未装配客户端主类时静默降级。 */
class ForgeClientHudBehaviourTest {

    /** 假传输与绑定其上的 dispatcher：可把编码后的产品包直接投递给 HUD 处理器。 */
    private static final class Harness {

        private final FakeTransport transport = new FakeTransport();
        private final PacketDispatcher dispatcher =
                new PacketDispatcher(transport, new top.wcpe.mc.mpmt.protocol.PacketCodec());
        private final ForgeClientHud hud = new ForgeClientHud();

        Harness() {
            hud.register(dispatcher);
        }

        void deliver(ServerHudMessagePacket hudPacket) {
            transport.receive(new top.wcpe.mc.mpmt.protocol.PacketCodec().encode(hudPacket));
        }
    }

    @Test
    @DisplayName("每类 HUD 均被记录为不可变快照")
    void 全类型记录快照() {
        for (HudKind kind : HudKind.values()) {
            Harness harness = new Harness();

            harness.deliver(new ServerHudMessagePacket(kind, "文本-" + kind, "副标题", 1000L));

            ForgeHudSnapshot snapshot = harness.hud.snapshot();
            assertNotNull(snapshot, "HUD 类型 " + kind + " 未记录快照");
            assertEquals(kind, snapshot.kind());
            assertEquals("文本-" + kind, snapshot.text());
            assertEquals("副标题", snapshot.subtitle());
            assertEquals(1000L, snapshot.durationMillis());
        }
    }

    @Test
    @DisplayName("未注册 dispatcher 时快照为空，清除后回到空态")
    void 未注册与清除() {
        ForgeClientHud hud = new ForgeClientHud();
        assertNull(hud.snapshot());

        Harness harness = new Harness();
        harness.deliver(new ServerHudMessagePacket(HudKind.CHAT, "内容", "", 0L));
        assertNotNull(harness.hud.snapshot());

        harness.hud.clear();
        assertNull(harness.hud.snapshot());
    }

    @Test
    @DisplayName("客户端主类不可用时注册仍成功，仅渲染被跳过")
    void 客户端不可用时降级() {
        Harness harness = new Harness();

        harness.deliver(new ServerHudMessagePacket(HudKind.TOAST, "提示", "副提示", 0L));

        assertNotNull(harness.hud.snapshot());
        assertEquals(HudKind.TOAST, harness.hud.snapshot().kind());
        assertEquals("提示", harness.hud.snapshot().text());
    }

    @Test
    @DisplayName("重复注册以后注册的处理器为准，快照随最新报文更新")
    void 重复注册以后者为准() {
        Harness harness = new Harness();
        harness.hud.register(harness.dispatcher);

        harness.deliver(new ServerHudMessagePacket(HudKind.ACTIONBAR, "第一", "", 0L));
        assertEquals("第一", harness.hud.snapshot().text());
        harness.deliver(new ServerHudMessagePacket(HudKind.ACTIONBAR, "第二", "", 0L));
        assertEquals("第二", harness.hud.snapshot().text());
    }

    @Test
    @DisplayName("快照类型为不可变记录")
    void 快照为不可变记录() {
        ForgeHudSnapshot snapshot = new ForgeHudSnapshot(HudKind.TITLE, "标题", "副", 500L);

        assertEquals(HudKind.TITLE, snapshot.kind());
        assertEquals("标题", snapshot.text());
        assertEquals("副", snapshot.subtitle());
        assertEquals(500L, snapshot.durationMillis());
        assertNotNull(snapshot.toString());
        assertTrue(ForgeHudSnapshot.class.isRecord());
    }

    @Test
    @DisplayName("会话构造拒绝空通道 / 空版本 / 空标识提供器")
    void 会话构造拒绝空依赖() {
        assertThrows(NullPointerException.class, () -> new ForgeClientSession(
                (top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeTypedPayloadChannel) null, "v", () -> "c"));
        assertThrows(NullPointerException.class, () -> new ForgeClientSession(
                Forge121MinecraftTestSupport.newTypedChannel(), null, () -> "c"));
        assertThrows(NullPointerException.class, () -> new ForgeClientSession(
                Forge121MinecraftTestSupport.newTypedChannel(), "v", null));
    }

    @Test
    @DisplayName("无通道的测试会话不允许连接真实网络，断线清理后回到空态")
    void 测试会话拒绝真实连接() throws Exception {
        Constructor<ForgeClientSession> constructor = ForgeClientSession.class.getDeclaredConstructor(
                ForgeClientHud.class, String.class,
                top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider.class);
        constructor.setAccessible(true);
        ForgeClientSession session = constructor.newInstance(
                new ForgeClientHud(), "v", (top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider) () -> "c");

        assertThrows(IllegalStateException.class,
                () -> session.join(Forge121MinecraftTestSupport.newClientConnection()));
        assertNull(session.networkFeature());
        assertNull(session.hudSnapshot());

        // 未连接时断线清理为幂等空操作
        session.disconnect();
        assertNull(session.networkFeature());
    }

    /** 假传输：仅记录出站报文，不触网络。 */
    private static final class FakeTransport implements TransportPort {

        private static final ConnectionHandle SERVER = new ConnectionHandle() { };

        private final List<byte[]> sent = new ArrayList<>();
        private BiConsumer<ConnectionHandle, byte[]> receiver;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            throw new UnsupportedOperationException("测试客户端不向任意连接发送");
        }

        @Override
        public void send(byte[] data) {
            sent.add(data.clone());
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 1_048_576;
        }

        void receive(byte[] data) {
            receiver.accept(SERVER, data);
        }
    }
}
