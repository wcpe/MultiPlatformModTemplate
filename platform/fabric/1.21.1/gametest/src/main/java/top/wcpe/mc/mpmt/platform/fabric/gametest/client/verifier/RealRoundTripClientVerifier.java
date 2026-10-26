package top.wcpe.mc.mpmt.platform.fabric.gametest.client.verifier;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import top.wcpe.mc.mpmt.acceptance.report.DefaultScenarioMatrix;
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
            DefaultScenarioMatrix.REAL_ROUND_TRIP.substring(DefaultScenarioMatrix.REAL_ROUND_TRIP.indexOf('/') + 1);

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
        // 断言「本次连接内收到过场景自己发的那条 ACTIONBAR」，而不是「最后一条是不是它」。
        // 产品握手流程会在进世界后连续下发 ACTIONBAR / TOAST / CHAT 三条演示 HUD，
        // 后到的演示 HUD 会把场景发的那条覆盖掉（lastRendered 是单值），只看最后一条必然误判。
        for (ServerHudMessagePacket hud : FabricHudRenderer.receivedHuds()) {
            if (hud.getKind() == HudKind.ACTIONBAR
                    && RealRoundTripServerScenario.HUD_TEXT.equals(hud.getText())) {
                return VerifyOutcome.ok(
                        "{\"hud\":\"" + hud.getText() + "\"}", "真实网络往返 HUD 已渲染");
            }
        }
        ServerHudMessagePacket last = FabricHudRenderer.lastRendered();
        String seen = last == null ? "(无)" : "kind=" + last.getKind() + " text=" + last.getText();
        return VerifyOutcome.fail(
                "未收到期望的 ACTIONBAR HUD（期望 text=" + RealRoundTripServerScenario.HUD_TEXT
                        + "，最后一条=" + seen + "）");
    }
}
