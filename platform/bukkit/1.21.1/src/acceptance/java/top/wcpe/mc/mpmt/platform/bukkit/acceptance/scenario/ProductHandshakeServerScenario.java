package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/**
 * 验证客户端经真实产品通道完成握手与标识上报。
 *
 * <p>客户端复用 Fabric 验收伴侣的 {@code ProductHandshakeClientVerifier}（异构 FR-11②）。
 */
public final class ProductHandshakeServerScenario extends BukkitServerScenario {

    @Override
    public String id() {
        return "product-handshake";
    }

    @Override
    public void run(ServerGameTestContext context) {
        awaitClientReady(CLIENT_READY_TIMEOUT_MS);
        runClientStep("verify-handshake", "{}");
    }
}
