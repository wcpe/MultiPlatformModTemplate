package top.wcpe.mc.mpmt.platform.neoforge.acceptance.client.verifier;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import top.wcpe.mc.mpmt.core.client.ClientNetworkFeature;
import top.wcpe.mc.mpmt.core.client.HandshakeClientService;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.client.ClientVerifier;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.client.RealServerClientContext;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.client.VerifyOutcome;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.client.VerifyStep;
import top.wcpe.mc.mpmt.platform.neoforge.proxy.ClientProxy;

/** 验证真实产品握手已完成（ServerHello 接受 + ClientIdReport 后欢迎消息）。 */
@OnlyIn(Dist.CLIENT)
public final class ProductHandshakeClientVerifier implements ClientVerifier {

    private static final String EXPECTED_WELCOME = "欢迎";

    @Override
    public String scenarioId() {
        return "product-handshake";
    }

    @Override
    public VerifyOutcome poll(VerifyStep step, RealServerClientContext context) {
        if (!"verify-handshake".equals(step.stepId())) {
            return VerifyOutcome.error("未知步骤：" + step.stepId());
        }
        ClientNetworkFeature feature = ClientProxy.networkFeature();
        HandshakeClientService handshake = feature.handshakeClient();
        if (!handshake.isAccepted()) {
            return null;
        }
        String sessionId = handshake.sessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return VerifyOutcome.fail("产品握手已接受但缺少 sessionId");
        }
        if (!EXPECTED_WELCOME.equals(handshake.lastServerMessage())) {
            return null;
        }
        return VerifyOutcome.ok(
                "{\"sessionId\":\"" + sessionId + "\"}", "产品握手与标识上报通过");
    }
}
