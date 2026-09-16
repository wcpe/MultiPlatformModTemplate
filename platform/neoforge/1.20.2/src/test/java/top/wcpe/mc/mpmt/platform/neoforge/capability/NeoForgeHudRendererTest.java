package top.wcpe.mc.mpmt.platform.neoforge.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.neoforge.NeoForgeTestSupport;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * NeoForge 跨端 HUD 渲染：四类呈现分支与快照记录。
 *
 * <p>渲染需客户端单例，测试注入 {@link Minecraft} 与 {@link Gui} 替身；快照在渲染前记录，
 * 故客户端缺失的场景也可稳定断言。
 */
class NeoForgeHudRendererTest {

    /** 客户端单例是静态的，测试间必须还原。 */
    @AfterEach
    void 还原客户端单例() {
        NeoForgeTestSupport.setStatic(Minecraft.class, "instance", null);
    }

    @BeforeEach
    void 清空渲染快照() {
        NeoForgeTestSupport.setStatic(NeoForgeHudRenderer.class, "lastRendered", null);
    }

    @Test
    @DisplayName("渲染前即记录快照，四类消息类型均可读回")
    void 快照记录() {
        installClient();

        for (HudKind kind : HudKind.values()) {
            NeoForgeHudRenderer.render(new ServerHudMessagePacket(kind, "正文-" + kind, "副文", 1000L));

            assertNotNull(NeoForgeHudRenderer.lastRendered(), kind + " 应记录快照");
            assertEquals(kind, NeoForgeHudRenderer.lastRendered().getKind());
            assertEquals("正文-" + kind, NeoForgeHudRenderer.lastRendered().getText());
        }
    }

    @Test
    @DisplayName("TITLE 写入主副标题")
    void 标题分支() {
        Gui gui = installClient();

        NeoForgeHudRenderer.render(new ServerHudMessagePacket(HudKind.TITLE, "主标题", "副标题", 2000L));

        assertEquals("主标题", text(gui, "title"));
        assertEquals("副标题", text(gui, "subtitle"));
    }

    @Test
    @DisplayName("TITLE 空副标题不覆盖既有副标题")
    void 标题空副标题不覆盖() {
        Gui gui = installClient();
        gui.setSubtitle(Component.literal("旧副标题"));

        NeoForgeHudRenderer.render(new ServerHudMessagePacket(HudKind.TITLE, "仅标题", "", 0L));

        assertEquals("仅标题", text(gui, "title"));
        assertEquals("旧副标题", text(gui, "subtitle"));
    }

    @Test
    @DisplayName("ACTIONBAR 写入动作栏覆盖消息")
    void 动作栏分支() {
        Gui gui = installClient();

        NeoForgeHudRenderer.render(
                new ServerHudMessagePacket(HudKind.ACTIONBAR, "动作栏文本", "", 0L));

        assertEquals("动作栏文本", text(gui, "overlayMessageString"));
        assertNull(text(gui, "title"), "动作栏不应写入标题");
    }

    @Test
    @DisplayName("CHAT 经聊天组件进入聊天历史")
    void 聊天分支() {
        Gui gui = installClient();
        List<GuiMessage> history = chatHistory(gui);

        NeoForgeHudRenderer.render(new ServerHudMessagePacket(HudKind.CHAT, "聊天文本", "", 0L));

        assertEquals(1, history.size());
        assertEquals("聊天文本", history.get(0).content().getString());
        assertNull(text(gui, "overlayMessageString"), "聊天不应误写动作栏");
        assertNull(text(gui, "title"), "聊天不应误写标题");
    }

    @Test
    @DisplayName("TOAST 分支不误写标题、动作栏与聊天")
    void 提示分支() {
        Gui gui = installClient();
        List<GuiMessage> history = chatHistory(gui);

        NeoForgeHudRenderer.render(new ServerHudMessagePacket(HudKind.TOAST, "提示文本", "副文", 500L));
        NeoForgeHudRenderer.render(new ServerHudMessagePacket(HudKind.TOAST, "纯提示", "", 500L));

        assertNull(text(gui, "title"), "提示不应写入标题");
        assertNull(text(gui, "overlayMessageString"), "提示不应写入动作栏");
        assertTrue(history.isEmpty(), "提示不应进入聊天历史");
    }

    @Test
    @DisplayName("最近一次快照始终指向最后一条消息")
    void 快照指向最后一条() {
        installClient();

        NeoForgeHudRenderer.render(new ServerHudMessagePacket(HudKind.CHAT, "第一条", "", 0L));
        NeoForgeHudRenderer.render(new ServerHudMessagePacket(HudKind.TOAST, "第二条", "", 0L));

        assertEquals(HudKind.TOAST, NeoForgeHudRenderer.lastRendered().getKind());
        assertEquals("第二条", NeoForgeHudRenderer.lastRendered().getText());
    }

    @Test
    @DisplayName("空消息包被拒绝")
    void 空包被拒绝() {
        assertThrows(NullPointerException.class, () -> NeoForgeHudRenderer.render(null));
    }

    /** 注入客户端替身：真实 Gui 状态承载渲染结果，聊天与提示所需的客户端设施齐备。 */
    private static Gui installClient() {
        Minecraft minecraft = NeoForgeTestSupport.newClient();
        Gui gui = NeoForgeTestSupport.allocate(Gui.class);
        ChatComponent chat = NeoForgeTestSupport.allocate(ChatComponent.class);
        NeoForgeTestSupport.set(chat, "minecraft", minecraft);
        NeoForgeTestSupport.set(chat, "allMessages", new ArrayList<GuiMessage>());
        NeoForgeTestSupport.set(chat, "trimmedMessages", new ArrayList<GuiMessage.Line>());
        NeoForgeTestSupport.set(chat, "recentChat", new net.minecraft.util.ArrayListDeque<String>());
        NeoForgeTestSupport.set(gui, "chat", chat);
        NeoForgeTestSupport.set(minecraft, "gui", gui);
        NeoForgeTestSupport.set(minecraft, "options", chatOptions());
        NeoForgeTestSupport.set(minecraft, "font", font());
        NeoForgeTestSupport.set(minecraft, "toast", toastComponent(minecraft));
        NeoForgeTestSupport.setStatic(Minecraft.class, "instance", minecraft);
        return gui;
    }

    /** 最小提示组件替身：只要求可接受 addToast 调用。 */
    private static net.minecraft.client.gui.components.toasts.ToastComponent toastComponent(
            Minecraft minecraft) {
        net.minecraft.client.gui.components.toasts.ToastComponent component =
                NeoForgeTestSupport.allocate(
                        net.minecraft.client.gui.components.toasts.ToastComponent.class);
        NeoForgeTestSupport.set(component, "minecraft", minecraft);
        NeoForgeTestSupport.set(component, "queued", new java.util.ArrayDeque<>());
        NeoForgeTestSupport.set(component, "visible", new java.util.ArrayList<>());
        return component;
    }

    /** 最小字体替身：文本宽度按字符数估算，不依赖字体资源。 */
    private static net.minecraft.client.gui.Font font() {
        net.minecraft.client.gui.Font font = NeoForgeTestSupport.allocate(net.minecraft.client.gui.Font.class);
        NeoForgeTestSupport.set(font, "lineHeight", 9);
        NeoForgeTestSupport.set(font, "splitter", new net.minecraft.client.StringSplitter(
                (codePoint, style) -> 6.0F));
        NeoForgeTestSupport.set(font, "filterFishyGlyphs", false);
        return font;
    }

    @SuppressWarnings("unchecked")
    private static List<GuiMessage> chatHistory(Gui gui) {
        Object chat = NeoForgeTestSupport.getField(gui, NeoForgeTestSupport.field(Gui.class, "chat"));
        return (List<GuiMessage>) NeoForgeTestSupport.getField(chat,
                NeoForgeTestSupport.field(ChatComponent.class, "allMessages"));
    }

    /** 最小选项集替身：聊天宽度与缩放被读取，其余不触碰。 */
    private static net.minecraft.client.Options chatOptions() {
        net.minecraft.client.Options options = NeoForgeTestSupport.allocate(net.minecraft.client.Options.class);
        NeoForgeTestSupport.set(options, "chatWidth", option(1.0D));
        NeoForgeTestSupport.set(options, "chatScale", option(1.0D));
        NeoForgeTestSupport.set(options, "chatHeightFocused", option(1.0D));
        NeoForgeTestSupport.set(options, "chatHeightUnfocused", option(1.0D));
        NeoForgeTestSupport.set(options, "chatOpacity", option(1.0D));
        NeoForgeTestSupport.set(options, "chatColors", booleanOption(true));
        return options;
    }

    private static net.minecraft.client.OptionInstance<Boolean> booleanOption(boolean value) {
        net.minecraft.client.OptionInstance<Boolean> instance =
                NeoForgeTestSupport.allocate(net.minecraft.client.OptionInstance.class);
        NeoForgeTestSupport.set(instance, "value", value);
        return instance;
    }

    private static net.minecraft.client.OptionInstance<Double> option(double value) {
        net.minecraft.client.OptionInstance<Double> instance =
                NeoForgeTestSupport.allocate(net.minecraft.client.OptionInstance.class);
        NeoForgeTestSupport.set(instance, "value", value);
        return instance;
    }

    private static String text(Gui gui, String fieldName) {
        Object value = NeoForgeTestSupport.getField(gui,
                NeoForgeTestSupport.field(Gui.class, fieldName));
        return value == null ? null : ((Component) value).getString();
    }

}
