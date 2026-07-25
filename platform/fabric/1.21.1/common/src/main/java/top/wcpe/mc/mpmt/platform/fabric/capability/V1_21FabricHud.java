package top.wcpe.mc.mpmt.platform.fabric.capability;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricHud;
import top.wcpe.mc.mpmt.platform.fabric.version.HudSnapshot;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** Fabric 1.21.1 客户端 HUD 实现，覆盖标题、动作栏、聊天与提示。 */
@Environment(EnvType.CLIENT)
public final class V1_21FabricHud implements FabricHud {

    private volatile HudSnapshot snapshot;

    @Override
    public void register(PacketDispatcher dispatcher) {
        dispatcher.on(
                PacketIds.SERVER_HUD_MESSAGE,
                (connection, packet) -> {
                    ServerHudMessagePacket hud = (ServerHudMessagePacket) packet;
                    snapshot =
                            new HudSnapshot(
                                    hud.getKind(), hud.getText(), hud.getSubtitle(), hud.getDurationMillis());
                    Minecraft.getInstance().execute(() -> render(hud));
                });
    }

    @Override
    public HudSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public void clear() {
        snapshot = null;
    }

    private static void render(ServerHudMessagePacket hud) {
        Minecraft minecraft = Minecraft.getInstance();
        Gui gui = minecraft.gui;
        Component text = Component.literal(hud.getText());
        switch (hud.getKind()) {
            case TITLE:
                gui.setTitle(text);
                if (!hud.getSubtitle().isEmpty()) {
                    gui.setSubtitle(Component.literal(hud.getSubtitle()));
                }
                break;
            case ACTIONBAR:
                gui.setOverlayMessage(text, false);
                break;
            case TOAST:
                SystemToast.add(
                        minecraft.getToasts(),
                        SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        text,
                        hud.getSubtitle().isEmpty() ? null : Component.literal(hud.getSubtitle()));
                break;
            case CHAT:
            default:
                gui.getChat().addMessage(text);
                break;
        }
    }
}
