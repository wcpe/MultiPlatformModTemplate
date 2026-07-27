package top.wcpe.mc.mpmt.platform.fabric.gametest.scenario;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.platform.fabric.MpmtFabricBootstrap;
import top.wcpe.mc.mpmt.platform.fabric.gametest.FabricServerGameTestContext;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricConnectionHandle;
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
                ((FabricServerGameTestContext) context).server().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            context.fail("无在线玩家");
            return;
        }
        FabricConnectionHandle connection =
                MpmtFabricBootstrap.serverTransport().connectionFor(players.get(0));
        MpmtFabricBootstrap.serverNetworkFeature()
                .hudMessageService()
                .send(connection, HudKind.ACTIONBAR, HUD_TEXT);
    }
}
