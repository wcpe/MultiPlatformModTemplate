package top.wcpe.mc.mpmt.platform.forge.modern.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;


import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.platform.forge.modern.ForgeTestSupport;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeTypedPayloadChannel;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Forge 26.2 客户端 HUD：注册、快照记录与四类呈现分支。
 *
 * <p>收包路径用真实 {@link PacketDispatcher} + 假传输驱动，渲染路径注入 {@link Minecraft} 与
 * {@link Gui}/{@link Hud} 替身后读回内部状态断言分支真实生效。
 */
class ForgeClientHudTest {

    /** 客户端单例是静态的，测试间必须还原，避免污染其它用例。 */
    @AfterEach
    void 还原客户端单例() {
        ForgeTestSupport.setStatic(Minecraft.class, "instance", null);
    }

    @Test
    @DisplayName("注册后收到动作栏消息即写入覆盖消息与动作栏快照")
    void 动作栏分支() {
        Hud hud = installClient();
        ForgeClientHud clientHud = new ForgeClientHud();
        FakeTransport transport = new FakeTransport();
        PacketDispatcher dispatcher = new PacketDispatcher(transport, new PacketCodec());
        clientHud.register(dispatcher);

        receive(transport, new ServerHudMessagePacket(HudKind.ACTIONBAR, "动作栏文本", "", 0L));

        assertEquals("动作栏文本", text(hud, "overlayMessageString"));
        assertEquals("动作栏文本", clientHud.snapshot().text());
        assertSame(clientHud.snapshot(), clientHud.actionBarSnapshot());
        assertNull(field(hud, "title"));
    }

    @Test
    @DisplayName("收到标题消息写入主副标题并按毫秒折算停留 tick")
    void 标题分支() {
        Hud hud = installClient();
        ForgeClientHud clientHud = new ForgeClientHud();
        FakeTransport transport = new FakeTransport();
        clientHud.register(new PacketDispatcher(transport, new PacketCodec()));

        receive(transport, new ServerHudMessagePacket(HudKind.TITLE, "主标题", "副标题", 3000L));

        assertEquals("主标题", text(hud, "title"));
        assertEquals("副标题", text(hud, "subtitle"));
        assertEquals(60, field(hud, "titleStayTime"), "3000ms 应折算为 60 tick 停留");
        assertEquals(10, field(hud, "titleFadeInTime"));
        assertEquals(10, field(hud, "titleFadeOutTime"));
        assertNull(clientHud.actionBarSnapshot(), "标题不应写入动作栏快照");
    }

    @Test
    @DisplayName("标题空副标题不覆盖，零时长不调整时序")
    void 标题分支边界() {
        Hud hud = installClient();
        hud.setSubtitle(Component.literal("旧副标题"));
        ForgeClientHud clientHud = new ForgeClientHud();
        FakeTransport transport = new FakeTransport();
        clientHud.register(new PacketDispatcher(transport, new PacketCodec()));

        receive(transport, new ServerHudMessagePacket(HudKind.TITLE, "仅标题", "", 0L));

        assertEquals("仅标题", text(hud, "title"));
        assertEquals("旧副标题", text(hud, "subtitle"), "空副标题不应覆盖");
        assertEquals(0, field(hud, "titleStayTime"), "零时长不应调用 setTimes");
    }

    @Test
    @DisplayName("提示分支不误写标题与动作栏")
    void 提示分支() {
        Hud hud = installClient();
        ForgeClientHud clientHud = new ForgeClientHud();
        FakeTransport transport = new FakeTransport();
        clientHud.register(new PacketDispatcher(transport, new PacketCodec()));

        receive(transport, new ServerHudMessagePacket(HudKind.TOAST, "提示正文", "提示副文", 500L));
        receive(transport, new ServerHudMessagePacket(HudKind.TOAST, "纯提示", "", 500L));

        assertNull(field(hud, "title"), "提示不应写入标题");
        assertNull(field(hud, "overlayMessageString"), "提示不应写入覆盖消息");
        assertEquals(HudKind.TOAST, clientHud.snapshot().kind());
        assertEquals("纯提示", clientHud.snapshot().text());
    }

    @Test
    @DisplayName("聊天分支把消息交给聊天组件并标注为服务端系统消息")
    void 聊天分支() {
        Hud hud = installClient();
        List<String> accepted = new ArrayList<>();
        installChatFilter(hud, accepted);
        ForgeClientHud clientHud = new ForgeClientHud();
        FakeTransport transport = new FakeTransport();
        clientHud.register(new PacketDispatcher(transport, new PacketCodec()));

        receive(transport, new ServerHudMessagePacket(HudKind.CHAT, "系统消息", "", 0L));

        assertEquals(1, accepted.size(), "聊天分支应把消息交给聊天组件");
        assertEquals("系统消息", accepted.get(0));
        assertEquals(HudKind.CHAT, clientHud.snapshot().kind());
        assertNull(field(hud, "overlayMessageString"), "聊天不应误写动作栏");
        assertNull(field(hud, "title"), "聊天不应误写标题");
    }

    @Test
    @DisplayName("多类消息依次到达时快照按类型分别更新")
    void 快照分别更新() {
        installClient();
        ForgeClientHud hud = new ForgeClientHud();
        FakeTransport transport = new FakeTransport();
        hud.register(new PacketDispatcher(transport, new PacketCodec()));

        receive(transport, new ServerHudMessagePacket(HudKind.CHAT, "聊天快照", "", 0L));
        assertNotNull(hud.snapshot());
        assertEquals(HudKind.CHAT, hud.snapshot().kind());
        assertNull(hud.actionBarSnapshot(), "聊天不应写入动作栏快照");

        receive(transport, new ServerHudMessagePacket(HudKind.ACTIONBAR, "动作栏快照", "", 0L));
        assertEquals("动作栏快照", hud.actionBarSnapshot().text());

        receive(transport, new ServerHudMessagePacket(HudKind.TITLE, "后续标题", "副文", 100L));
        assertEquals(HudKind.TITLE, hud.snapshot().kind());
        assertEquals("动作栏快照", hud.actionBarSnapshot().text(), "标题不应覆盖动作栏快照");
    }

    @Test
    @DisplayName("清空后两类快照归零")
    void 清空快照() {
        installClient();
        ForgeClientHud hud = new ForgeClientHud();
        FakeTransport transport = new FakeTransport();
        hud.register(new PacketDispatcher(transport, new PacketCodec()));
        receive(transport, new ServerHudMessagePacket(HudKind.ACTIONBAR, "待清空", "", 0L));

        hud.clear();

        assertNull(hud.snapshot());
        assertNull(hud.actionBarSnapshot());
    }

    @Test
    @DisplayName("客户端未就绪时只记快照，不触碰渲染")
    void 客户端缺失只记快照() {
        ForgeTestSupport.setStatic(Minecraft.class, "instance", null);
        ForgeClientHud hud = new ForgeClientHud();
        FakeTransport transport = new FakeTransport();
        hud.register(new PacketDispatcher(transport, new PacketCodec()));

        receive(transport, new ServerHudMessagePacket(HudKind.TITLE, "离线标题", "", 1000L));

        assertEquals("离线标题", hud.snapshot().text());
        assertEquals(HudKind.TITLE, hud.snapshot().kind());
    }

    @Test
    @DisplayName("HUD 快照按值暴露类型、正文、副文与时长")
    void 快照取值() {
        ForgeHudSnapshot snapshot = new ForgeHudSnapshot(HudKind.TITLE, "正文", "副文", 1200L);

        assertEquals(HudKind.TITLE, snapshot.kind());
        assertEquals("正文", snapshot.text());
        assertEquals("副文", snapshot.subtitle());
        assertEquals(1200L, snapshot.durationMillis());
    }

    @Test
    @DisplayName("通道未就绪的会话拒绝真实连接")
    void 会话拒绝真实连接() {
        ForgeClientSession session = new ForgeClientSession(new ForgeClientHud(), "1.0.0", () -> "code");

        assertThrows(IllegalStateException.class, () -> session.join(null));
    }

    @Test
    @DisplayName("会话构造拒绝空参数")
    void 会话空参数防御() {
        ForgeTypedPayloadChannel channel = new ForgeTypedPayloadChannel(
                Identifier.fromNamespaceAndPath("mpmt", "hud-test"));

        assertThrows(NullPointerException.class,
                () -> new ForgeClientSession(channel, (String) null, () -> "code"));
        assertThrows(NullPointerException.class,
                () -> new ForgeClientSession(channel, "1.0.0",
                        (top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider) null));
        assertThrows(NullPointerException.class,
                () -> new ForgeClientSession((ForgeTypedPayloadChannel) null, "1.0.0", () -> "code"));
    }

    @Test
    @DisplayName("会话暴露 HUD 快照并在断线时清理")
    void 会话快照与清理() {
        installClient();
        ForgeClientHud hud = new ForgeClientHud();
        ForgeClientSession session = new ForgeClientSession(hud, "1.0.0", () -> "code");
        FakeTransport transport = new FakeTransport();
        hud.register(new PacketDispatcher(transport, new PacketCodec()));
        receive(transport, new ServerHudMessagePacket(HudKind.ACTIONBAR, "会话动作栏", "", 0L));

        assertEquals("会话动作栏", session.hudSnapshot().text());
        assertSame(hud.actionBarSnapshot(), session.actionBarSnapshot());

        session.disconnect();

        assertNull(session.hudSnapshot());
        assertNull(session.actionBarSnapshot());
        assertNull(session.networkFeature());
    }

    private static void receive(FakeTransport transport, ServerHudMessagePacket packet) {
        PacketCodec codec = new PacketCodec();
        transport.deliver(codec.encode(packet));
    }

    private static Hud installClient() {
        Minecraft minecraft = ForgeTestSupport.newClient();
        Gui gui = ForgeTestSupport.allocate(Gui.class);
        Hud hud = ForgeTestSupport.allocate(Hud.class);
        ChatComponent chat = ForgeTestSupport.allocate(ChatComponent.class);
        ForgeTestSupport.set(chat, "minecraft", minecraft);
        ForgeTestSupport.set(chat, "allMessages", new net.minecraft.util.ArrayListDeque<GuiMessage>());
        ForgeTestSupport.set(chat, "trimmedMessages",
                new net.minecraft.util.ArrayListDeque<GuiMessage.Line>());
        ForgeTestSupport.set(chat, "visibleMessageFilter",
                (java.util.function.Predicate<GuiMessage>) message -> true);
        ForgeTestSupport.set(chat, "recentChat", new net.minecraft.util.ArrayListDeque<String>());
        ForgeTestSupport.set(hud, "chat", chat);
        ForgeTestSupport.set(hud, "subtitleOverlay",
                ForgeTestSupport.allocate(net.minecraft.client.gui.components.SubtitleOverlay.class));
        ForgeTestSupport.set(gui, "hud", hud);
        ForgeTestSupport.set(gui, "toastManager", ForgeTestSupport.allocate(ToastManager.class));
        ForgeTestSupport.set(minecraft, "gui", gui);
        ForgeTestSupport.set(minecraft, "options", chatOptions());
        ForgeTestSupport.setStatic(Minecraft.class, "instance", minecraft);
        return hud;
    }

    /** 造最小选项集替身：聊天宽度与缩放的取值被读取，其余不触碰。 */
    private static net.minecraft.client.Options chatOptions() {
        net.minecraft.client.Options options = ForgeTestSupport.allocate(net.minecraft.client.Options.class);
        ForgeTestSupport.set(options, "chatWidth", option(1.0D));
        ForgeTestSupport.set(options, "chatScale", option(1.0D));
        ForgeTestSupport.set(options, "chatHeightFocused", option(1.0D));
        return options;
    }

    private static net.minecraft.client.OptionInstance<Double> option(double value) {
        // 替身绕过构造器分配，类字面量拿不到泛型实参；此处按调用方约定只放 Double，故就地抑制。
        @SuppressWarnings("unchecked")
        net.minecraft.client.OptionInstance<Double> instance =
                ForgeTestSupport.allocate(net.minecraft.client.OptionInstance.class);
        ForgeTestSupport.set(instance, "value", value);
        return instance;
    }

    /**
     * 把聊天组件的可见性过滤器换成记录器。
     *
     * <p>真实渲染路径需要字体与窗口尺寸，纯 JVM 不可得；过滤器是「消息进入活跃聊天前」的最后一站，
     * 在此记录既证明聊天分支被触达，又不引入渲染依赖。
     */
    private static void installChatFilter(Hud hud, List<String> accepted) {
        Object chat = field(hud, "chat");
        ForgeTestSupport.set(chat, "visibleMessageFilter",
                (java.util.function.Predicate<GuiMessage>) message -> {
                    accepted.add(message.content().getString());
                    return true;
                });
    }

    private static Object field(Object target, String name) {
        return ForgeTestSupport.getField(target, ForgeTestSupport.field(target.getClass(), name));
    }

    private static String text(Object target, String name) {
        Object value = field(target, name);
        return value == null ? null : ((Component) value).getString();
    }

    /** 假传输：记录出站字节并允许把入站字节喂回分发器。 */
    private static final class FakeTransport implements TransportPort {

        private static final ConnectionHandle SERVER = new ConnectionHandle() {
        };

        private BiConsumer<ConnectionHandle, byte[]> receiver;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            throw new UnsupportedOperationException("测试客户端不向任意连接发送");
        }

        @Override
        public void send(byte[] data) {
            throw new UnsupportedOperationException("测试不触发发送");
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 1_048_576;
        }

        private void deliver(byte[] data) {
            receiver.accept(SERVER, data);
        }
    }
}
