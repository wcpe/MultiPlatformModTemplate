package top.wcpe.mc.mpmt.platform.forge.modern.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** Forge 1.21.1 客户端 HUD，实现动作栏、标题、聊天与最小提示。 */
public final class ForgeClientHud {

    private volatile ForgeHudSnapshot snapshot;

    public void register(PacketDispatcher dispatcher) {
        dispatcher.on(
                PacketIds.SERVER_HUD_MESSAGE,
                (connection, packet) -> {
                    ServerHudMessagePacket hud = (ServerHudMessagePacket) packet;
                    snapshot = new ForgeHudSnapshot(
                            hud.getKind(), hud.getText(), hud.getSubtitle(), hud.getDurationMillis());
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft != null) {
                        minecraft.execute(() -> render(hud));
                    }
                });
    }

    public ForgeHudSnapshot snapshot() {
        return snapshot;
    }

    public void clear() {
        snapshot = null;
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
