package top.wcpe.mc.mpmt.platform.sponge.acceptance.scenario;

import java.util.UUID;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.platform.sponge.MpmtSpongePlugin;

/**
 * 经真实产品 HudMessageService 下发 ACTIONBAR，控制通道只排程客户端断言。
 *
 * <p>客户端复用 Fabric 验收伴侣的 {@code ClientHudClientVerifier}（异构 FR-11②）。
 */
public final class ClientHudServerScenario extends ServerScenario {

    /** 与 Fabric 客户端验证器约定的 HUD 文本。 */
    public static final String HUD_TEXT = "验收HUD";

    @Override
    public String suite() {
        return "acceptance";
    }

    @Override
    public String id() {
        return "client-hud";
    }

    @Override
    public void run(ServerGameTestContext context) {
        awaitClientReady();
        MpmtSpongePlugin plugin = SpongeScenarioSupport.productPlugin(context);
        UUID playerId = context.onMain(() -> SpongeScenarioSupport.onlinePlayerId(context));
        context.onMain(() -> sendHud(context, plugin, playerId));
        runClientStep("verify-hud", "{}");
    }

    private static void sendHud(
            ServerGameTestContext context, MpmtSpongePlugin plugin, UUID playerId) {
        ServerPlayer player = Sponge.server().player(playerId).orElse(null);
        context.assertTrue(player != null, "发送 HUD 时玩家已离线");
        plugin.sendActionBarHud(player, HUD_TEXT);
    }
}
