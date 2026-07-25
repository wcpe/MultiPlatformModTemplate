package top.wcpe.mc.mpmt.platform.fabric.gametest.client.verifier;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.CapabilityMessageTracker;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.ClientVerifier;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.RealServerClientContext;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.VerifyOutcome;
import top.wcpe.mc.mpmt.platform.fabric.gametest.client.VerifyStep;

/** capability 客户端验证器：断言真实客户端已收到首次加入或再次加入欢迎消息。 */
@Environment(EnvType.CLIENT)
public final class CapabilityClientVerifier implements ClientVerifier {

    private static final String WELCOME_FIRST = "欢迎首次加入服务器！";
    private static final String WELCOME_BACK = "欢迎回来！";

    @Override
    public String scenarioId() {
        return "capability-first-join";
    }

    @Override
    public VerifyOutcome poll(VerifyStep step, RealServerClientContext context) {
        if (!"capability-message".equals(step.stepId())) {
            return VerifyOutcome.error("未知步骤：" + step.stepId());
        }
        String message = CapabilityMessageTracker.lastMessage();
        if (message == null) {
            return null;
        }
        if (!WELCOME_FIRST.equals(message) && !WELCOME_BACK.equals(message)) {
            return VerifyOutcome.fail("capability 欢迎消息不符：" + message);
        }
        return VerifyOutcome.ok("{\"message\":\"" + message + "\"}", "客户端已收到 capability 欢迎消息");
    }
}
