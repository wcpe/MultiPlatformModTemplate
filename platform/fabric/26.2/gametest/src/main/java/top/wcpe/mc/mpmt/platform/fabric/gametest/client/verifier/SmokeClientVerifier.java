package top.wcpe.mc.mpmt.platform.fabric.gametest.client.verifier;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import top.wcpe.mc.mpmt.platform.fabric.capability.FabricHudRenderer;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.ClientVerifier;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.RealServerClientContext;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.VerifyOutcome;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.VerifyStep;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * 冒烟客户端验证器（scenarioId=smoke）：客户端已进世界即视为冒烟通过，验证"服务端驱动 ↔ 客户端联调"链路本身。
 * 经 {@code ServiceLoader} 被伴侣发现。
 */
@Environment(EnvType.CLIENT)
public final class SmokeClientVerifier implements ClientVerifier {

    @Override
    public String scenarioId() {
        return "smoke";
    }

    /** 期望收到的 HUD 文本（与服务端场景独立约定，FR-27）。 */
    private static final String EXPECTED_HUD_TEXT = "验收HUD";

    @Override
    public VerifyOutcome poll(VerifyStep step, RealServerClientContext context) {
        if (!"smoke-ready".equals(step.stepId())) {
            return VerifyOutcome.error("未知步骤：" + step.stepId());
        }
        // 客户端未进世界：本 tick 未判定、下 tick 再试
        if (context.client().player == null) {
            return null;
        }
        // 等服务端经产品通道下发的 HUD 被渲染并记录（FR-27）；未到则下 tick 再试
        ServerHudMessagePacket hud = FabricHudRenderer.lastRendered();
        if (hud == null) {
            return null;
        }
        if (hud.getKind() != HudKind.ACTIONBAR || !EXPECTED_HUD_TEXT.equals(hud.getText())) {
            return VerifyOutcome.fail("HUD 不符：kind=" + hud.getKind() + " text=" + hud.getText());
        }
        return VerifyOutcome.ok("{\"hud\":\"" + hud.getText() + "\"}", "客户端在线且 HUD 已渲染");
    }
}
