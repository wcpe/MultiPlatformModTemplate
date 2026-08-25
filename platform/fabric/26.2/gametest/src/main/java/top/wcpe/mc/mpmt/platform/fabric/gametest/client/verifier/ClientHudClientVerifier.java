package top.wcpe.mc.mpmt.platform.fabric.gametest.client.verifier;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import top.wcpe.mc.mpmt.platform.fabric.capability.FabricHudRenderer;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.ClientVerifier;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.RealServerClientContext;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.VerifyOutcome;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.VerifyStep;
import top.wcpe.mc.mpmt.platform.fabric.gametest.scenario.ClientHudServerScenario;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** 验证真实产品 HUD 已进入客户端 FabricHudRenderer 验收快照。 */
@Environment(EnvType.CLIENT)
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
        ServerHudMessagePacket hud = FabricHudRenderer.lastRendered(HudKind.ACTIONBAR);
        if (hud == null) {
            return null;
        }
        if (!ClientHudServerScenario.HUD_TEXT.equals(hud.getText())) {
            return null;
        }
        return VerifyOutcome.ok("{\"hud\":\"" + hud.getText() + "\"}", "产品 HUD 通过");
    }
}
