package top.wcpe.mc.mpmt.platform.fabric.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.fabric.ClientTestSupport;
import top.wcpe.mc.mpmt.platform.fabric.ClientTestSupport.ClientChat;
import top.wcpe.mc.mpmt.platform.fabric.FakePacketDispatcher;
import top.wcpe.mc.mpmt.platform.fabric.version.HudSnapshot;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Fabric 1.20.1 客户端 HUD 实现（L4 适配器）：快照暴露、清空、按 kind 渲染与 Toast 投递。
 *
 * <p>渲染经产品自身的包注册路径进入，断言的是快照内容与文本去向。
 */
class V1_20FabricHudTest {

    @Test
    @DisplayName("初始快照为空；收包后快照逐字段保真")
    void 快照内容保真() {
        Fixture fixture = new Fixture();

        assertNull(fixture.hud().snapshot(), "未收包时快照须为空");

        fixture.deliver(new ServerHudMessagePacket(HudKind.TITLE, "标题", "副标题", 1234L));

        HudSnapshot snapshot = fixture.hud().snapshot();
        assertNotNull(snapshot);
        assertEquals(HudKind.TITLE, snapshot.kind());
        assertEquals("标题", snapshot.text());
        assertEquals("副标题", snapshot.subtitle());
        assertEquals(1234L, snapshot.durationMillis());
    }

    @Test
    @DisplayName("clear 丢弃快照，避免旧会话状态泄漏")
    void 清空丢弃快照() {
        Fixture fixture = new Fixture();
        fixture.deliver(new ServerHudMessagePacket(HudKind.CHAT, "聊天", "", 0L));
        assertNotNull(fixture.hud().snapshot());

        fixture.hud().clear();

        assertNull(fixture.hud().snapshot());
    }

    @Test
    @DisplayName("TITLE 落到标题与副标题位；空副标题不覆盖")
    void 标题渲染() {
        Fixture fixture = new Fixture();

        fixture.deliver(new ServerHudMessagePacket(HudKind.TITLE, "主标题", "副标题", 0L));
        assertEquals("主标题", fixture.hudGui().titleText());
        assertEquals("副标题", fixture.hudGui().subtitleText());

        fixture.deliver(new ServerHudMessagePacket(HudKind.TITLE, "换标题", "", 0L));
        assertEquals("换标题", fixture.hudGui().titleText());
        assertEquals("副标题", fixture.hudGui().subtitleText());
    }

    @Test
    @DisplayName("ACTIONBAR 落到覆盖消息位；CHAT 进入聊天")
    void 覆盖消息与聊天渲染() {
        Fixture fixture = new Fixture();

        fixture.deliver(new ServerHudMessagePacket(HudKind.ACTIONBAR, "动作栏", "", 0L));
        assertEquals("动作栏", fixture.hudGui().overlayText());

        fixture.deliver(new ServerHudMessagePacket(HudKind.CHAT, "聊天文本", "", 0L));
        assertEquals(List.of("聊天文本"), ((ClientChat) fixture.hudGui().getChat()).messages());
    }

    @Test
    @DisplayName("TOAST 进入 Toast 队列，且不落到标题位")
    void Toast渲染() {
        Fixture fixture = new Fixture();

        fixture.deliver(new ServerHudMessagePacket(HudKind.TOAST, "Toast 正文", "", 0L));
        fixture.deliver(new ServerHudMessagePacket(HudKind.TOAST, "Toast 正文", "Toast 副文", 0L));

        assertEquals(2, ClientTestSupport.toasts(fixture.minecraft()).size());
        assertNull(fixture.hudGui().titleText());
    }

    @Test
    @DisplayName("同一通道重复 register 后仍按包 id 分发（收包路径稳定）")
    void 重复注册仍可分发() {
        Fixture fixture = new Fixture();
        fixture.hud().register(fixture.dispatcher().dispatcher());

        fixture.deliver(new ServerHudMessagePacket(HudKind.CHAT, "第二次注册", "", 0L));

        assertEquals("第二次注册", fixture.hud().snapshot().text());
    }

    /** 一次性夹具：客户端替身 + L4 HUD + 真实分发管线。 */
    private static final class Fixture {

        private final net.minecraft.client.Minecraft minecraft;
        private final ClientTestSupport.RecordingGui hudGui;
        private final V1_20FabricHud hud = new V1_20FabricHud();
        private final FakePacketDispatcher dispatcher = new FakePacketDispatcher();

        Fixture() {
            this.minecraft = ClientTestSupport.newClient();
            this.hudGui = ClientTestSupport.installRecordingHud(minecraft);
            hud.register(dispatcher.dispatcher());
        }

        net.minecraft.client.Minecraft minecraft() {
            return minecraft;
        }

        ClientTestSupport.RecordingGui hudGui() {
            return hudGui;
        }

        V1_20FabricHud hud() {
            return hud;
        }

        FakePacketDispatcher dispatcher() {
            return dispatcher;
        }

        void deliver(ServerHudMessagePacket packet) {
            ClientTestSupport.useClient(minecraft);
            dispatcher.deliverTo(packet);
            ClientTestSupport.drainClientTasks();
        }
    }
}
