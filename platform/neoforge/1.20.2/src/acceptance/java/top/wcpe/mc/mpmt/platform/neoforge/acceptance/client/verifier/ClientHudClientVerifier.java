package top.wcpe.mc.mpmt.platform.neoforge.acceptance.client.verifier;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.client.ClientVerifier;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.client.RealServerClientContext;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.client.VerifyOutcome;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.client.VerifyStep;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.scenario.ClientHudServerScenario;
import top.wcpe.mc.mpmt.platform.neoforge.capability.NeoForgeHudRenderer;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** 验证真实产品 HUD 已进入客户端 NeoForgeHudRenderer 验收快照。 */
@OnlyIn(Dist.CLIENT)
public final class ClientHudClientVerifier implements ClientVerifier {

    @Override
    public String scenarioId() {
        return "client-hud";
    }

    @Override
    public VerifyOutcome poll(VerifyStep step, RealServerClientContext context) {
        if (!"verify-hud".equals(step.stepId())) {
            return VerifyOutcome.error("未知步骤：" + step.stepId());
        }
        ServerHudMessagePacket hud = NeoForgeHudRenderer.lastRendered();
        if (hud == null) {
            return null;
        }
        if (hud.getKind() != HudKind.ACTIONBAR
                || !ClientHudServerScenario.HUD_TEXT.equals(hud.getText())) {
            return VerifyOutcome.fail("HUD 不符：kind=" + hud.getKind() + " text=" + hud.getText());
        }
        return VerifyOutcome.ok("{\"hud\":\"" + hud.getText() + "\"}", "产品 HUD 通过");
    }
}
