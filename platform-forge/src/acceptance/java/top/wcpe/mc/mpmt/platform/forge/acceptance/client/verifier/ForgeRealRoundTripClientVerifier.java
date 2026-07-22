package top.wcpe.mc.mpmt.platform.forge.acceptance.client.verifier;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.platform.forge.acceptance.client.ClientVerifier;
import top.wcpe.mc.mpmt.platform.forge.acceptance.client.RealServerClientContext;
import top.wcpe.mc.mpmt.platform.forge.acceptance.client.VerifyOutcome;
import top.wcpe.mc.mpmt.platform.forge.acceptance.client.VerifyStep;
import top.wcpe.mc.mpmt.platform.forge.acceptance.scenario.ForgeRealRoundTripServerScenario;
import top.wcpe.mc.mpmt.platform.forge.capability.ForgeHudRenderer;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * real-round-trip 客户端验证：进世界且 ACTIONBAR 验收 HUD 已渲染。
 * 仅客户端（{@link OnlyIn}(Dist.CLIENT)）。
 */
@OnlyIn(Dist.CLIENT)
public final class ForgeRealRoundTripClientVerifier implements ClientVerifier {

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
        ServerHudMessagePacket hud = ForgeHudRenderer.lastRendered();
        if (hud == null) {
            return null;
        }
        if (hud.getKind() != HudKind.ACTIONBAR
                || !ForgeRealRoundTripServerScenario.HUD_TEXT.equals(hud.getText())) {
            return VerifyOutcome.fail("HUD 不符：kind=" + hud.getKind() + " text=" + hud.getText());
        }
        return VerifyOutcome.ok("{\"hud\":\"" + hud.getText() + "\"}", "真实网络往返 HUD 已渲染");
    }
}
