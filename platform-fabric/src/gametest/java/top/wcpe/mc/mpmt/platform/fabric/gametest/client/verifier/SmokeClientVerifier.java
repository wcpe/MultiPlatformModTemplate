package top.wcpe.mc.mpmt.platform.fabric.gametest.client.verifier;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.ClientVerifier;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.RealServerClientContext;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.VerifyOutcome;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.VerifyStep;

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

    @Override
    public VerifyOutcome poll(VerifyStep step, RealServerClientContext context) {
        if (!"smoke-ready".equals(step.stepId())) {
            return VerifyOutcome.error("未知步骤：" + step.stepId());
        }
        // 客户端已进世界（有玩家实体）即冒烟通过；否则本 tick 未判定、下 tick 再试
        return context.client().player != null ? VerifyOutcome.ok("{}", "客户端在线") : null;
    }
}
