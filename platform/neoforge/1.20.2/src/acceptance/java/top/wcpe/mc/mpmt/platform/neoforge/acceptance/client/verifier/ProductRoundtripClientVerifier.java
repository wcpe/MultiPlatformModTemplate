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

/**
 * 验证产品客户端在心跳往返后仍处于已握手状态。
 *
 * <p>tip 方向为 S2C Ping / C2S Pong，由产品 HeartbeatService 自动应答；服务端已断言会话 RTT，
 * 客户端侧只确认产品握手仍有效。
 */
@OnlyIn(Dist.CLIENT)
public final class ProductRoundtripClientVerifier implements ClientVerifier {

    @Override
    public String scenarioId() {
        return "product-roundtrip";
    }

    @Override
    public VerifyOutcome poll(VerifyStep step, RealServerClientContext context) {
        if (!"verify-roundtrip".equals(step.stepId())) {
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
        return VerifyOutcome.ok(
                "{\"sessionId\":\"" + sessionId + "\"}", "产品心跳往返后客户端会话仍有效");
    }
}
