package top.wcpe.mc.mpmt.platform.fabric.gametest.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/** capability 验收消息跟踪器，仅记录真实客户端收到的欢迎游戏消息。 */
@Environment(EnvType.CLIENT)
public final class CapabilityMessageTracker {

    private static final String WELCOME_FIRST = "欢迎首次加入服务器！";
    private static final String WELCOME_BACK = "欢迎回来！";

    private static volatile String lastMessage;

    private CapabilityMessageTracker() {}

    /** 注册游戏消息监听。 */
    public static void register() {
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> {
                    String text = message.getString();
                    if (WELCOME_FIRST.equals(text) || WELCOME_BACK.equals(text)) {
                        lastMessage = text;
                    }
                });
    }

    /** 返回最近收到的 capability 欢迎消息。 */
    public static String lastMessage() {
        return lastMessage;
    }
}
