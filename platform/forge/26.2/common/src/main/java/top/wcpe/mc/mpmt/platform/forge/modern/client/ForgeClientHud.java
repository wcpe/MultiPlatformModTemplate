package top.wcpe.mc.mpmt.platform.forge.modern.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** Forge 26.2 客户端 HUD，实现动作栏、标题、聊天与最小提示。 */
public final class ForgeClientHud {

    private volatile ForgeHudSnapshot snapshot;
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

    /** 返回当前会话最近一次动作栏消息，供跨类型 HUD 验收稳定读取。 */
    public ForgeHudSnapshot actionBarSnapshot() {
        return actionBarSnapshot;
    }

    public void clear() {
        snapshot = null;
        actionBarSnapshot = null;
    }

    private static void render(ServerHudMessagePacket hud) {
        Minecraft minecraft = Minecraft.getInstance();
        // 26.2 将动作栏/标题/聊天等 HUD 元素从 Gui 拆分到 Gui.hud
        Hud gameHud = minecraft.gui.hud;
        Component text = Component.literal(hud.getText());
        switch (hud.getKind()) {
            case ACTIONBAR -> gameHud.setOverlayMessage(text, false);
            case TITLE -> renderTitle(gameHud, hud, text);
            case TOAST -> SystemToast.add(
                    minecraft.gui.toastManager(),
                    SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    text,
                    hud.getSubtitle().isEmpty() ? null : Component.literal(hud.getSubtitle()));
            // 26.2 拆分 addMessage：服务端下发的系统消息走 addServerSystemMessage
            case CHAT -> gameHud.getChat().addServerSystemMessage(text);
        }
    }

    private static void renderTitle(Hud gameHud, ServerHudMessagePacket hud, Component title) {
        gameHud.setTitle(title);
        if (!hud.getSubtitle().isEmpty()) {
            gameHud.setSubtitle(Component.literal(hud.getSubtitle()));
        }
        if (hud.getDurationMillis() > 0L) {
            int stayTicks = (int) Math.min(Integer.MAX_VALUE, hud.getDurationMillis() / 50L);
            gameHud.setTimes(10, Math.max(1, stayTicks), 10);
        }
    }
}
