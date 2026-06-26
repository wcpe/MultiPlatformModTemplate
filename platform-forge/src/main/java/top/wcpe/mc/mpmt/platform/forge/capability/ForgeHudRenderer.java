package top.wcpe.mc.mpmt.platform.forge.capability;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Forge 跨端 HUD 渲染（L3 客户端，FR-27）：收 S2C {@link ServerHudMessagePacket}，按 {@link
 * top.wcpe.mc.mpmt.protocol.packet.HudKind} 在客户端线程呈现（title / actionbar / toast / chat）。
 *
 * <p>本类<b>仅客户端</b>（{@link OnlyIn}(Dist.CLIENT)）——引用 {@link Minecraft} 等客户端专有类型，服务端不得加载。
 * 收包在网络线程：先记录快照（{@link #lastRendered}，供验收读），再经 {@link Minecraft#execute} 切到客户端线程
 * 渲染（ADR-0013：不在网络线程碰渲染态）。与 {@code FabricHudRenderer} 同义 API（Forge 1.20.1 官方映射，类名一致）。
 */
@OnlyIn(Dist.CLIENT)
public final class ForgeHudRenderer {

    private static volatile ServerHudMessagePacket lastRendered;

    private ForgeHudRenderer() {
        // 工具类不实例化
    }

    /** 最近一次收到并处理的 HUD（验收用，可能为空）。 */
    public static ServerHudMessagePacket lastRendered() {
        return lastRendered;
    }

    /** 处理一个 HUD 包：记录快照后切客户端线程渲染。 */
    public static void render(ServerHudMessagePacket hud) {
        lastRendered = hud;
        Minecraft.getInstance().execute(() -> renderOnClientThread(hud));
    }

    private static void renderOnClientThread(ServerHudMessagePacket hud) {
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
