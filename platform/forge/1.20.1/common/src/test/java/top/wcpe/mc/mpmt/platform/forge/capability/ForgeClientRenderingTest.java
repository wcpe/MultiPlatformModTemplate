package top.wcpe.mc.mpmt.platform.forge.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.forge.ForgeMinecraftTestSupport;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** 客户端 HUD 渲染：四种呈现类型均真实落到客户端线程并经替身 {@code Gui} 呈现。 */
class ForgeHudRendererTest {

    @BeforeAll
    static void 安装客户端替身() {
        ForgeMinecraftTestSupport.installClientInstance();
    }

    @Test
    @DisplayName("每类 HUD 均记录快照并切到客户端线程渲染")
    void 全类型渲染() {
        for (HudKind kind : HudKind.values()) {
            ServerHudMessagePacket hud = new ServerHudMessagePacket(kind, "文本-" + kind, "副标题", 1000L);

            ForgeHudRenderer.render(hud);

            assertSame(hud, ForgeHudRenderer.lastRendered());
            assertEquals(1, ForgeMinecraftTestSupport.drainClientTasks());
        }
    }

    @Test
    @DisplayName("标题无副标题、时长为零时不设置副标题与停留时间")
    void 标题边界分支() {
        ForgeHudRenderer.render(new ServerHudMessagePacket(HudKind.TITLE, "仅标题", "", 0L));

        assertEquals(1, ForgeMinecraftTestSupport.drainClientTasks());
        assertEquals("仅标题", ForgeHudRenderer.lastRendered().getText());
        assertTrue(ForgeHudRenderer.lastRendered().getSubtitle().isEmpty());
    }

    @Test
    @DisplayName("聊天与动作栏渲染不抛异常且记录最新快照")
    void 聊天与动作栏渲染() {
        ForgeHudRenderer.render(new ServerHudMessagePacket(HudKind.CHAT, "聊天内容", "", 0L));
        ForgeHudRenderer.render(new ServerHudMessagePacket(HudKind.ACTIONBAR, "动作栏内容", "", 0L));

        assertEquals(2, ForgeMinecraftTestSupport.drainClientTasks());
        assertEquals(HudKind.ACTIONBAR, ForgeHudRenderer.lastRendered().getKind());
        assertEquals("动作栏内容", ForgeHudRenderer.lastRendered().getText());
    }
}
