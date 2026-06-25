package top.wcpe.mc.mpmt.platform.fabric.capability;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Fabric 跨端 HUD 渲染（L3 客户端，FR-27）：收 S2C {@link ServerHudMessagePacket}，按 {@link
 * top.wcpe.mc.mpmt.protocol.packet.HudKind} 在客户端线程呈现（title / actionbar / toast / chat）。
 *
 * <p>收包在网络线程：先记录快照（{@link #lastRendered}，供验收读），再经 {@code Minecraft#execute} 切到客户端线程
 * 渲染（ADR-0013：不在网络线程碰渲染态）。
 */
@Environment(EnvType.CLIENT)
public final class FabricHudRenderer {

    private static volatile ServerHudMessagePacket lastRendered;

    private FabricHudRenderer() {
        // 工具类不实例化
    }

    /** 最近一次收到并处理的 HUD（验收用，可能为空）。 */
    public static ServerHudMessagePacket lastRendered() {
        return lastRendered;
    }

    /** 在客户端收发管线上注册 HUD 收包处理器。 */
    public static void register(PacketDispatcher dispatcher) {
        dispatcher.on(
                PacketIds.SERVER_HUD_MESSAGE,
                (connection, packet) -> {
                    ServerHudMessagePacket hud = (ServerHudMessagePacket) packet;
                    lastRendered = hud;
                    Minecraft.getInstance().execute(() -> render(hud));
                });
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
                        SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                        text,
                        hud.getSubtitle().isEmpty() ? null : Component.literal(hud.getSubtitle()));
                break;
            case CHAT:
                gui.getChat().addMessage(text);
                break;
            default:
                gui.getChat().addMessage(text);
                break;
        }
    }
}
