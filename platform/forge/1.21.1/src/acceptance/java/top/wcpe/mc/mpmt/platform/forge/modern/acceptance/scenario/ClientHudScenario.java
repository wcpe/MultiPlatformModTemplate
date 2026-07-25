package top.wcpe.mc.mpmt.platform.forge.modern.acceptance.scenario;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.platform.forge.modern.MpmtForge121Mod;
import top.wcpe.mc.mpmt.platform.forge.modern.acceptance.ForgeServerGameTestContext;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeConnectionHandle;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;

/** 经产品 HudMessageService 下发 ACTIONBAR，控制通道只排程客户端断言。 */
public final class ClientHudScenario extends ServerScenario {

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
        MpmtForge121Mod.serverNetworkFeature()
                .hudMessageService()
                .send(new ForgeConnectionHandle(players.get(0)), HudKind.ACTIONBAR, HUD_TEXT);
    }
}
