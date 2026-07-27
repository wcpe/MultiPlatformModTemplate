package top.wcpe.mc.mpmt.platform.fabric.capability;

import java.lang.reflect.Method;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Fabric 跨端 HUD 渲染（L3 客户端，FR-27）：收 S2C {@link ServerHudMessagePacket}，按 kind
 * 在客户端线程呈现（title / actionbar / toast / chat）。
 *
 * <p>Toast 枚举在 1.20 / 1.21 漂移，经反射解析；失败时退化为聊天消息，避免 main 绑死单一 MC API。
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

    /** 断线时清空 HUD 快照，避免旧会话状态泄漏到下一 play 连接。 */
    public static void clear() {
        lastRendered = null;
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
        Hud clientHud = minecraft.gui.hud;
        Component text = Component.literal(hud.getText());
        switch (hud.getKind()) {
            case TITLE:
                clientHud.setTitle(text);
                if (!hud.getSubtitle().isEmpty()) {
                    clientHud.setSubtitle(Component.literal(hud.getSubtitle()));
                }
                break;
            case ACTIONBAR:
                clientHud.setOverlayMessage(text, false);
                break;
            case TOAST:
                addToast(
                        minecraft,
                        text,
                        hud.getSubtitle().isEmpty()
                                ? null
                                : Component.literal(hud.getSubtitle()));
                break;
            case CHAT:
                clientHud.getChat().addClientSystemMessage(text);
                break;
            default:
                clientHud.getChat().addClientSystemMessage(text);
                break;
        }
    }

    /** Toast API 跨版本反射；无法解析时退化为聊天。 */
    private static void addToast(Minecraft minecraft, Component title, Component subtitle) {
        Object toastId = resolveToastId();
        if (toastId == null) {
            minecraft.gui.hud.getChat().addClientSystemMessage(title);
            return;
        }
        try {
            ToastManager toasts = minecraft.gui.toastManager();
            Method add =
                    SystemToast.class.getMethod(
                            "add",
                            ToastManager.class,
                            toastId.getClass(),
                            Component.class,
                            Component.class);
            add.invoke(null, toasts, toastId, title, subtitle);
        } catch (ReflectiveOperationException e) {
            minecraft.gui.hud.getChat().addClientSystemMessage(title);
        }
    }

    private static Object resolveToastId() {
        // 1.20：SystemToast.SystemToastIds.PERIODIC_NOTIFICATION
        Object id = enumConstant("net.minecraft.client.gui.components.toasts.SystemToast$SystemToastIds", "PERIODIC_NOTIFICATION");
        if (id != null) {
            return id;
        }
        // 1.21 可能改名 / 挪包：再试常见常量
        id = enumConstant("net.minecraft.client.gui.components.toasts.SystemToast$SystemToastId", "PERIODIC_NOTIFICATION");
        if (id != null) {
            return id;
        }
        return enumConstant("net.minecraft.client.gui.components.toasts.SystemToast$SystemToastIds", "NARRATOR_TOGGLE");
    }

    private static Object enumConstant(String className, String name) {
        try {
            Class<?> type = Class.forName(className);
            if (!type.isEnum()) {
                return null;
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), name);
            return value;
        } catch (ClassNotFoundException | IllegalArgumentException ignored) {
            return null;
        }
    }
}
