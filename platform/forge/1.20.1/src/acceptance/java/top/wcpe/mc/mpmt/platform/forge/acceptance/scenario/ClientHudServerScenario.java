package top.wcpe.mc.mpmt.platform.forge.acceptance.scenario;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.platform.forge.MpmtForgeMod;
import top.wcpe.mc.mpmt.platform.forge.acceptance.ForgeServerGameTestContext;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeConnectionHandle;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;

/** 经真实产品 HudMessageService 下发 HUD，控制通道只排程客户端断言。 */
public final class ClientHudServerScenario extends ServerScenario {

    /** 与客户端验证器约定的 HUD 文本。 */
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
        context.onMain(() -> sendHud(context));
        runClientStep("verify-hud", "{}");
    }

    private static void sendHud(ServerGameTestContext context) {
        List<ServerPlayer> players =
                ((ForgeServerGameTestContext) context).server().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            context.fail("无在线玩家");
            return;
        }
        ForgeConnectionHandle connection =
                MpmtForgeMod.serverTransport().connectionFor(players.get(0));
        MpmtForgeMod.serverNetworkFeature()
                .hudMessageService()
                .send(connection, HudKind.ACTIONBAR, HUD_TEXT);
    }
}
