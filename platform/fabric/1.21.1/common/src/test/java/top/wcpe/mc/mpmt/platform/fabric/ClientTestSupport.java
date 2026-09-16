package top.wcpe.mc.mpmt.platform.fabric;

import java.util.List;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;

/**
 * 客户端 HUD 测试替身：为纯 JVM 下的客户端渲染提供可观测的接收端。
 *
 * <p>MC 客户端对象（{@code Minecraft} / {@code Gui} / 聊天组件 / Toast 容器）在测试中经
 * {@link MinecraftTestSupport#allocateInstance} 造出后，需要补齐"渲染目标"字段才能被真实渲染代码调用。
 * 本类集中这些注入，并记录渲染结果供断言。
 */
public final class ClientTestSupport {

    private ClientTestSupport() {
        // 工具类不实例化
    }

    /** 造一个可注入的客户端实例（{@code Minecraft.instance} 已指向它）。 */
    public static net.minecraft.client.Minecraft newClient() {
        net.minecraft.client.Minecraft minecraft =
                MinecraftTestSupport.allocateInstance(net.minecraft.client.Minecraft.class);
        MinecraftTestSupport.set(minecraft, "singleplayerServer", null);
        // MC 的 Toast / 聊天换行要先量文本宽度；跳过构造函数后需补上可用的字体替身
        MinecraftTestSupport.setField(
                minecraft,
                MinecraftTestSupport.field(net.minecraft.client.Minecraft.class, "font"),
                stubFont());
        // 事件循环队列与运行线程由构造函数初始化；跳过构造函数后须补上，
        // 产品的 `Minecraft.getInstance().execute(...)` 才能把渲染任务派发出去
        MinecraftTestSupport.set(minecraft, "gameThread", Thread.currentThread());
        MinecraftTestSupport.setField(
                minecraft,
                MinecraftTestSupport.field(
                        net.minecraft.util.thread.BlockableEventLoop.class, "pendingRunnables"),
                new java.util.ArrayDeque<>());
        MinecraftTestSupport.setField(
                net.minecraft.client.Minecraft.class,
                MinecraftTestSupport.field(net.minecraft.client.Minecraft.class, "instance"),
                minecraft);
        return minecraft;
    }

    /** 造一个常量宽度的字体替身（仅满足文本量宽，不引入真实字体资源）。 */
    public static StubFont stubFont() {
        StubFont font = MinecraftTestSupport.allocateInstance(StubFont.class);
        MinecraftTestSupport.set(font, "lineHeight", 9);
        return font;
    }

    /** 字体替身：所有文本量宽返回固定值，避免真实字体集依赖。 */
    public static class StubFont extends net.minecraft.client.gui.Font {

        private static final int CHAR_WIDTH = 6;

        private StubFont() {
            // 仅满足编译器；实例经 Unsafe 分配，本构造函数不会执行
            super(null, false);
        }

        @Override
        public int width(String text) {
            return text == null ? 0 : text.length() * CHAR_WIDTH;
        }

        @Override
        public int width(net.minecraft.network.chat.FormattedText text) {
            return width(text.getString());
        }

        @Override
        public int width(net.minecraft.util.FormattedCharSequence text) {
            return CHAR_WIDTH;
        }
    }

    /** 重新指向给定客户端实例（同一 JVM 内其他用例可能覆盖过静态 {@code instance}）。 */
    public static void useClient(net.minecraft.client.Minecraft minecraft) {
        MinecraftTestSupport.setField(
                net.minecraft.client.Minecraft.class,
                MinecraftTestSupport.field(net.minecraft.client.Minecraft.class, "instance"),
                minecraft);
    }

    /** 执行并清空客户端事件循环中已排队的任务（渲染在下一 tick，需显式驱动）。 */
    public static int drainClientTasks() {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        int executed = 0;
        while (minecraft.pollTask()) {
            executed++;
        }
        return executed;
    }

    /** 给客户端装上记录型 HUD，并装好记录型 Toast 容器；返回可断言的 HUD。 */
    public static RecordingGui installRecordingHud(net.minecraft.client.Minecraft minecraft) {
        RecordingGui hud = MinecraftTestSupport.allocateInstance(RecordingGui.class);
        ClientChat chat = MinecraftTestSupport.allocateInstance(ClientChat.class);
        chat.prepare();
        hud.chat = chat;
        MinecraftTestSupport.setField(
                minecraft, MinecraftTestSupport.field(net.minecraft.client.Minecraft.class, "gui"), hud);

        ToastReceiver toasts = MinecraftTestSupport.allocateInstance(ToastReceiver.class);
        toasts.prepare();
        MinecraftTestSupport.setField(
                minecraft,
                MinecraftTestSupport.field(net.minecraft.client.Minecraft.class, "toast"),
                toasts);
        return hud;
    }

    /** 取当前客户端收到的 Toast（按投递顺序），供断言渲染去向。 */
    public static List<Toast> toasts(net.minecraft.client.Minecraft minecraft) {
        return ((ToastReceiver) minecraft.getToasts()).receivedToasts();
    }

    /**
     * 记录型 HUD：把标题 / 副标题 / 覆盖消息暴露为可读文本，并替换聊天组件为记录型实现。
     *
     * <p>MC 的 {@code Gui} 把标题与覆盖消息存放在私有字段、聊天经 {@code getChat()} 取得；
     * 测试读取它们以断言"HUD 真的按 kind 渲染到了正确位置"。
     */
    public static class RecordingGui extends Gui {

        //1.21.1 的 Gui 直接持有 chat 字段，覆写 getChat 即可隔离渲染


        private ChatComponent chat;

        private RecordingGui() {
            // 仅满足编译器；实例经 Unsafe 分配，本构造函数不会执行
            super(null);
        }

        @Override
        public ChatComponent getChat() {
            return chat;
        }

        /** 当前标题文本（未设置时为 null）。 */
        public String titleText() {
            return textOf("title");
        }

        /** 当前副标题文本（未设置时为 null）。 */
        public String subtitleText() {
            return textOf("subtitle");
        }

        /** 当前覆盖消息（actionbar）文本（未设置时为 null）。 */
        public String overlayText() {
            return textOf("overlayMessageString");
        }

        private String textOf(String fieldName) {
            Component component =
                    (Component)
                            MinecraftTestSupport.getField(
                                    this, MinecraftTestSupport.field(Gui.class, fieldName));
            return component == null ? null : component.getString();
        }
    }

    /** 记录型聊天：承载消息文本，避免真实字体与客户端选项依赖。 */
    public static class ClientChat extends ChatComponent {

        private List<String> messages;

        private ClientChat() {
            // 仅满足编译器；实例经 Unsafe 分配，本构造函数不会执行
            super(null);
        }

        void prepare() {
            messages = new java.util.ArrayList<>();
            // recentChat 在 1.20.1 为 List、1.21.1+ 为 ArrayListDeque（final 字段，类型必须匹配）
            MinecraftTestSupport.set(this, "recentChat", MinecraftTestSupport.newListLike("recentChat", this));
            MinecraftTestSupport.set(this, "allMessages", new java.util.ArrayList<>());
            MinecraftTestSupport.set(this, "trimmedMessages", new java.util.ArrayList<>());
            MinecraftTestSupport.set(this, "messageDeletionQueue", new java.util.ArrayList<>());
        }

        @Override
        public void addMessage(Component component) {
            messages.add(component.getString());
        }

        /** 已收到的消息文本（按接收顺序）。 */
        public List<String> messages() {
            return messages;
        }
    }

    /** 记录型 Toast 容器：覆写入队入口，避免真实字体与客户端选项依赖。 */
    public static class ToastReceiver extends ToastComponent {

        private List<Toast> received;

        private ToastReceiver() {
            // 仅满足编译器；实例经 Unsafe 分配，本构造函数不会执行
            super(null);
        }

        void prepare() {
            received = new java.util.ArrayList<>();
            MinecraftTestSupport.set(this, "queued", new java.util.ArrayDeque<>());
            MinecraftTestSupport.set(this, "visible", new java.util.ArrayList<>());
            MinecraftTestSupport.set(this, "occupiedSlots", new java.util.BitSet(5));
        }

        @Override
        public void addToast(Toast toast) {
            received.add(toast);
        }

        List<Toast> receivedToasts() {
            return received;
        }
    }
}
