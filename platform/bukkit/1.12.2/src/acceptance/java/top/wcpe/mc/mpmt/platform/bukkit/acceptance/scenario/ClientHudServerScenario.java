package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import java.util.Collection;
import org.bukkit.entity.Player;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.BukkitServerGameTestContext;

/**
 * 经真实产品 HudMessageService 下发 ACTIONBAR，控制通道只排程客户端断言。
 *
 * <p>客户端复用 Fabric 验收伴侣的 {@code ClientHudClientVerifier}（异构 FR-11②）。
 * 产品 API 经 {@link ProductPluginAccess} 运行期调用（acceptance 不编译依赖产品主类）。
 */
public final class ClientHudServerScenario extends BukkitServerScenario {

    /** 与 Fabric 客户端验证器约定的 HUD 文本。 */
    public static final String HUD_TEXT = "验收HUD";

    @Override
    public String id() {
        return "client-hud";
    }

    @Override
    public void run(ServerGameTestContext context) {
        awaitClientReady(CLIENT_READY_TIMEOUT_MS);
        // 给握手后产品四类 HUD 演示包留出窗口，再发验收 ACTIONBAR（对齐 1.21.1 / R4）
        context.waitTicks(40);
        context.onMain(() -> sendHud(context));
        runClientStep("verify-hud", "{}");
    }

    private static void sendHud(ServerGameTestContext context) {
        BukkitServerGameTestContext bukkit = (BukkitServerGameTestContext) context;
        Collection<? extends Player> players = bukkit.server().getOnlinePlayers();
        if (players.isEmpty()) {
            context.fail("无在线客户端玩家");
            return;
        }
        ProductPluginAccess.sendActionBarHud(players.iterator().next(), HUD_TEXT);
    }
}
