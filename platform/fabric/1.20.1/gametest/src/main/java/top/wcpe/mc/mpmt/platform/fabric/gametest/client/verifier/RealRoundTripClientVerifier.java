package top.wcpe.mc.mpmt.platform.fabric.gametest.client.verifier;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.platform.fabric.capability.FabricHudRenderer;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.ClientVerifier;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.RealServerClientContext;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.VerifyOutcome;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.VerifyStep;
import top.wcpe.mc.mpmt.platform.fabric.gametest.scenario.RealRoundTripServerScenario;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** real-round-trip 客户端验证：进世界且 ACTIONBAR 验收 HUD 已渲染。 */
@Environment(EnvType.CLIENT)
public final class RealRoundTripClientVerifier implements ClientVerifier {

    private static final String SCENARIO_ID =
            P1ScenarioMatrix.REAL_ROUND_TRIP.substring(P1ScenarioMatrix.REAL_ROUND_TRIP.indexOf('/') + 1);

    @Override
    public String scenarioId() {
        return SCENARIO_ID;
    }

    @Override
    public VerifyOutcome poll(VerifyStep step, RealServerClientContext context) {
        if (!"real-round-trip-ready".equals(step.stepId())) {
            return VerifyOutcome.error("未知步骤：" + step.stepId());
        }
        if (context.client().player == null) {
            return null;
        }
        ServerHudMessagePacket hud = FabricHudRenderer.lastRendered();
        if (hud == null) {
            return null;
        }
        if (hud.getKind() != HudKind.ACTIONBAR || !RealRoundTripServerScenario.HUD_TEXT.equals(hud.getText())) {
            return VerifyOutcome.fail("HUD 不符：kind=" + hud.getKind() + " text=" + hud.getText());
        }
        return VerifyOutcome.ok("{\"hud\":\"" + hud.getText() + "\"}", "真实网络往返 HUD 已渲染");
    }
}
