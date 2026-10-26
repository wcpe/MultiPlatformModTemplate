package top.wcpe.mc.mpmt.platform.forge.modern.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** Forge 1.21.1 客户端 HUD，实现动作栏、标题、聊天与最小提示。 */
public final class ForgeClientHud {

    private volatile ForgeHudSnapshot snapshot;
    /**
     * 仅记录最近一条 ACTIONBAR，避免被后到的 TITLE/TOAST/CHAT 覆盖。
     *
     * <p>产品握手会依次下发 TITLE → ACTIONBAR → TOAST → CHAT，CHAT 是最后一包；
     * 若断言只看 {@link #snapshot}，就会被演示 CHAT 覆盖而永远等不到验收 ACTIONBAR。
     */
    private volatile ForgeHudSnapshot actionBarSnapshot;

    public void register(PacketDispatcher dispatcher) {
        dispatcher.on(
                PacketIds.SERVER_HUD_MESSAGE,
                (connection, packet) -> {
                    ServerHudMessagePacket hud = (ServerHudMessagePacket) packet;
                    ForgeHudSnapshot next = new ForgeHudSnapshot(
                            hud.getKind(), hud.getText(), hud.getSubtitle(), hud.getDurationMillis());
                    snapshot = next;
                    if (hud.getKind() == HudKind.ACTIONBAR) {
                        actionBarSnapshot = next;
                    }
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft != null) {
                        minecraft.execute(() -> render(hud));
                    }
                });
    }

    public ForgeHudSnapshot snapshot() {
        return snapshot;
    }

    /** 最近一条 ACTIONBAR 快照（供 HUD 验收断言，不受其它类型覆盖）。 */
    public ForgeHudSnapshot actionBarSnapshot() {
        return actionBarSnapshot;
    }

    public void clear() {
        snapshot = null;
        actionBarSnapshot = null;
    }

    private static void render(ServerHudMessagePacket hud) {
        Minecraft minecraft = Minecraft.getInstance();
        Gui gui = minecraft.gui;
        Component text = Component.literal(hud.getText());
        switch (hud.getKind()) {
            case ACTIONBAR -> gui.setOverlayMessage(text, false);
            case TITLE -> renderTitle(gui, hud, text);
            case TOAST -> SystemToast.add(
                    minecraft.getToasts(),
                    SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    text,
                    hud.getSubtitle().isEmpty() ? null : Component.literal(hud.getSubtitle()));
            case CHAT -> gui.getChat().addMessage(text);
            default -> gui.getChat().addMessage(text);
        }
    }

    private static void renderTitle(Gui gui, ServerHudMessagePacket hud, Component title) {
        gui.setTitle(title);
        if (!hud.getSubtitle().isEmpty()) {
            gui.setSubtitle(Component.literal(hud.getSubtitle()));
        }
        if (hud.getDurationMillis() > 0L) {
            int stayTicks = (int) Math.min(Integer.MAX_VALUE, hud.getDurationMillis() / 50L);
            gui.setTimes(10, Math.max(1, stayTicks), 10);
        }
    }
}
