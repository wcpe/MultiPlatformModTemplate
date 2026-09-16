package top.wcpe.mc.mpmt.platform.fabric.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.fabric.ClientTestSupport;
import top.wcpe.mc.mpmt.platform.fabric.ClientTestSupport.ClientChat;
import top.wcpe.mc.mpmt.platform.fabric.ClientTestSupport.RecordingGui;
import top.wcpe.mc.mpmt.platform.fabric.FakePacketDispatcher;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Fabric 跨端 HUD 渲染：按 kind 落到各自呈现位置。
 *
 * <p>渲染路径经产品自身的包分发注册进入（{@link FabricHudRenderer#register}），
 * 断言的是"文本真的送到了该去的地方"，而非仅调用一遍。
 */
class FabricHudRendererTest {

    @AfterEach
    void 清理Hud快照() {
        FabricHudRenderer.clear();
    }

    @Test
    @DisplayName("TITLE：主标题与副标题分别落到标题位，空副标题不覆盖已有副标题")
    void 标题渲染() {
        Fixture fixture = new Fixture();

        fixture.deliver(new ServerHudMessagePacket(HudKind.TITLE, "主标题", "副标题", 0L));
        assertEquals("主标题", fixture.hud().titleText());
        assertEquals("副标题", fixture.hud().subtitleText());

        fixture.deliver(new ServerHudMessagePacket(HudKind.TITLE, "仅主标题", "", 0L));
        assertEquals("仅主标题", fixture.hud().titleText());
        assertEquals("副标题", fixture.hud().subtitleText(), "空副标题不得覆盖已有副标题");
    }

    @Test
    @DisplayName("ACTIONBAR：文本落到覆盖消息位，不污染标题")
    void 覆盖消息渲染() {
        Fixture fixture = new Fixture();

        fixture.deliver(new ServerHudMessagePacket(HudKind.ACTIONBAR, "动作栏文本", "", 0L));

        assertEquals("动作栏文本", fixture.hud().overlayText());
        assertNull(fixture.hud().titleText());
    }

    @Test
    @DisplayName("CHAT：文本进入聊天，且不落到标题或覆盖消息")
    void 聊天渲染() {
        Fixture fixture = new Fixture();

        fixture.deliver(new ServerHudMessagePacket(HudKind.CHAT, "聊天文本", "", 0L));

        assertEquals(List.of("聊天文本"), ((ClientChat) fixture.hud().getChat()).messages());
        assertNull(fixture.hud().titleText());
        assertNull(fixture.hud().overlayText());
    }

    @Test
    @DisplayName("TOAST：文本进入 Toast 队列，不落到标题位")
    void Toast渲染() {
        Fixture fixture = new Fixture();

        fixture.deliver(new ServerHudMessagePacket(HudKind.TOAST, "Toast 正文", "", 0L));
        fixture.deliver(new ServerHudMessagePacket(HudKind.TOAST, "Toast 正文", "Toast 副文", 0L));

        assertEquals(2, ClientTestSupport.toasts(fixture.client()).size(), "两次 TOAST 应各投递一个");
        assertNull(fixture.hud().titleText(), "TOAST 不得落到标题位");
    }

    @Test
    @DisplayName("注册后按 HUD 包 id 分发；快照随收包更新，断线清理丢弃快照")
    void 注册与快照() {
        Fixture fixture = new Fixture();

        assertNull(FabricHudRenderer.lastRendered());

        fixture.deliver(new ServerHudMessagePacket(HudKind.CHAT, "经分发", "", 0L));

        // 快照携带的是线缆解码后的新实例，比较内容而非引用
        assertEquals("经分发", FabricHudRenderer.lastRendered().getText());

        FabricHudRenderer.clear();
        assertNull(FabricHudRenderer.lastRendered(), "断线清理须丢弃快照");
    }

    @Test
    @DisplayName("快照只保留最近一次 HUD，不累积历史")
    void 快照只保留最近一次() {
        Fixture fixture = new Fixture();

        ServerHudMessagePacket first = new ServerHudMessagePacket(HudKind.TITLE, "第一次", "", 0L);
        ServerHudMessagePacket second = new ServerHudMessagePacket(HudKind.ACTIONBAR, "第二次", "", 0L);

        fixture.deliver(first);
        assertEquals("第一次", FabricHudRenderer.lastRendered().getText());
        assertEquals(HudKind.TITLE, FabricHudRenderer.lastRendered().getKind());

        fixture.deliver(second);
        assertEquals("第二次", FabricHudRenderer.lastRendered().getText());
        assertEquals(HudKind.ACTIONBAR, FabricHudRenderer.lastRendered().getKind());
    }

    /** 一次性夹具：客户端替身 + 经产品注册路径取得的分发器。 */
    private static final class Fixture {

        private final RecordingGui hud;
        private final FakePacketDispatcher dispatcher = new FakePacketDispatcher();

        Fixture() {
            this.minecraft = ClientTestSupport.newClient();
            this.hud = ClientTestSupport.installRecordingHud(minecraft);
            FabricHudRenderer.register(dispatcher.dispatcher());
        }

        private final net.minecraft.client.Minecraft minecraft;

        RecordingGui hud() {
            return hud;
        }

        net.minecraft.client.Minecraft client() {
            return minecraft;
        }

        /** 投递 HUD 包并驱动客户端渲染任务队列（渲染在下一 tick 执行）。 */
        void deliver(ServerHudMessagePacket packet) {
            ClientTestSupport.useClient(minecraft);
            dispatcher.deliverTo(packet);
            ClientTestSupport.drainClientTasks();
        }
    }
}
