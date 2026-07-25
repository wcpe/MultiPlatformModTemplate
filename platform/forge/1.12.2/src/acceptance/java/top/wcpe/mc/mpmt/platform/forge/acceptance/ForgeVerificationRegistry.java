package top.wcpe.mc.mpmt.platform.forge.acceptance;

import java.util.HashMap;
import java.util.Map;
import top.wcpe.mc.mpmt.acceptance.control.RunStepPacket;
import top.wcpe.mc.mpmt.core.client.ClientNetworkFeature;
import top.wcpe.mc.mpmt.platform.forge.MpmtForgeMod;
import top.wcpe.mc.mpmt.platform.forge.hud.ForgeHudSnapshot;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;

/** R5 客户端 required scenario 验证器注册表。 */
final class ForgeVerificationRegistry {

    private static final String EXPECTED_HUD_TEXT = "验收HUD";

    private final Map<String, Verifier> verifiers = new HashMap<>();

    ForgeVerificationRegistry() {
        verifiers.put("product-handshake", this::verifyHandshake);
        verifiers.put("product-roundtrip", this::verifyRoundtrip);
        verifiers.put("client-hud", this::verifyHud);
        verifiers.put("forge-client-optional", this::verifyOptional);
    }

    ForgeVerifyOutcome poll(RunStepPacket step) {
        Verifier verifier = verifiers.get(step.getScenarioId());
        if (verifier == null) {
            return ForgeVerifyOutcome.error("无客户端验证器：scenarioId=" + step.getScenarioId());
        }
        return verifier.poll(step);
    }

    void clear() {
        // 无跨步骤可变状态
    }

    private ForgeVerifyOutcome verifyHandshake(RunStepPacket step) {
        if (!"verify-handshake".equals(step.getStepId())) {
            return unknownStep(step);
        }
        ClientNetworkFeature feature = MpmtForgeMod.session().networkFeature();
        if (feature == null || !feature.handshakeClient().isAccepted()) {
            return null;
        }
        String sessionId = feature.handshakeClient().sessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return ForgeVerifyOutcome.fail("产品握手已接受但缺少 sessionId");
        }
        if (!"欢迎".equals(feature.handshakeClient().lastServerMessage())) {
            return null;
        }
        return ForgeVerifyOutcome.ok(
                "{\"sessionId\":\"" + sessionId + "\"}", "产品握手与标识上报通过");
    }

    /**
     * 产品心跳方向是 S2C Ping / C2S Pong，由 HeartbeatService 自动应答。
     *
     * <p>客户端侧只确认握手仍有效，禁止再注册 PONG 处理器或主动发 Ping（会覆盖/冲突产品心跳处理器，
     * 且服务端不会对客户端 Ping 回 Pong）。与 Fabric / Forge 1.20 验证器对齐。
     */
    private ForgeVerifyOutcome verifyRoundtrip(RunStepPacket step) {
        if (!"verify-roundtrip".equals(step.getStepId())) {
            return unknownStep(step);
        }
        ClientNetworkFeature feature = MpmtForgeMod.session().networkFeature();
        if (feature == null || !feature.handshakeClient().isAccepted()) {
            return null;
        }
        String sessionId = feature.handshakeClient().sessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return ForgeVerifyOutcome.fail("产品握手已接受但缺少 sessionId");
        }
        return ForgeVerifyOutcome.ok(
                "{\"sessionId\":\"" + sessionId + "\"}", "产品心跳往返后客户端会话仍有效");
    }


    private ForgeVerifyOutcome verifyHud(RunStepPacket step) {
        if (!"verify-hud".equals(step.getStepId())) {
            return unknownStep(step);
        }
        ForgeHudSnapshot snapshot = MpmtForgeMod.session().hudSnapshot();
        if (snapshot == null) {
            return null;
        }
        if (snapshot.kind() != HudKind.ACTIONBAR || !EXPECTED_HUD_TEXT.equals(snapshot.text())) {
            return ForgeVerifyOutcome.fail(
                    "HUD 不符：kind=" + snapshot.kind() + " text=" + snapshot.text());
        }
        return ForgeVerifyOutcome.ok(
                "{\"hud\":\"" + snapshot.text() + "\"}", "产品 HUD 通过");
    }

    private ForgeVerifyOutcome verifyOptional(RunStepPacket step) {
        if (!"verify-optional".equals(step.getStepId())) {
            return unknownStep(step);
        }
        if (!MpmtForgeMod.isConnected()) {
            return null;
        }
        if (!MpmtForgeMod.optionalCheckAccepted()) {
            return ForgeVerifyOutcome.fail("未观察到允许缺失服务端 Forge mod 的网络检查");
        }
        if (!MpmtForgeMod.remoteForgeProductAbsent()) {
            return ForgeVerifyOutcome.fail("服务端 Forge mod 列表仍包含 mpmt");
        }
        return ForgeVerifyOutcome.ok(
                "{\"connected\":true,\"optional\":true,\"remoteForgeProductAbsent\":true}",
                "client-only/optional 检查与无服务端 Forge 产品连接通过");
    }

    private static ForgeVerifyOutcome unknownStep(RunStepPacket step) {
        return ForgeVerifyOutcome.error("未知步骤：" + step.getStepId());
    }

    private interface Verifier {
        ForgeVerifyOutcome poll(RunStepPacket step);
    }
}
