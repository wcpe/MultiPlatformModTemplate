package top.wcpe.mc.mpmt.platform.forge.acceptance.client.verifier;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import top.wcpe.mc.mpmt.platform.forge.acceptance.client.ClientVerifier;
import top.wcpe.mc.mpmt.platform.forge.acceptance.client.RealServerClientContext;
import top.wcpe.mc.mpmt.platform.forge.acceptance.client.VerifyOutcome;
import top.wcpe.mc.mpmt.platform.forge.acceptance.client.VerifyStep;
import top.wcpe.mc.mpmt.platform.forge.capability.ForgeHudRenderer;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * 冒烟客户端验证器（scenarioId=smoke）：客户端已进世界且服务端经产品通道下发的 HUD 已被渲染并记录即通过，
 * 验证"服务端驱动 ↔ Forge 客户端联调 + 跨端 HUD"链路本身（FR-27）。经 {@code ServiceLoader} 被伴侣发现。
 *
 * <p><b>仅客户端</b>（{@link OnlyIn}(Dist.CLIENT)）：读 {@link ForgeHudRenderer#lastRendered()} 客户端快照。
 */
@OnlyIn(Dist.CLIENT)
public final class ForgeSmokeClientVerifier implements ClientVerifier {

    /** 期望收到的 HUD 文本（须与 ForgeSmokeServerScenario 发的一致，FR-27）。 */
    private static final String EXPECTED_HUD_TEXT = "验收HUD";

    @Override
    public String scenarioId() {
        return "smoke";
    }

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
        ServerHudMessagePacket hud = ForgeHudRenderer.lastRendered();
        if (hud == null) {
            return null;
        }
        if (hud.getKind() != HudKind.ACTIONBAR || !EXPECTED_HUD_TEXT.equals(hud.getText())) {
            return VerifyOutcome.fail("HUD 不符：kind=" + hud.getKind() + " text=" + hud.getText());
        }
        return VerifyOutcome.ok("{\"hud\":\"" + hud.getText() + "\"}", "客户端在线且 HUD 已渲染");
    }
}
