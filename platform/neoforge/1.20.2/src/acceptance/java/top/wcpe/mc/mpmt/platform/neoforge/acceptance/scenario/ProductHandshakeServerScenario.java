package top.wcpe.mc.mpmt.platform.neoforge.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;

/**
 * 验证真实产品通道完成 ClientHello / ServerHello / ClientIdReport。
 *
 * <p>产品栈在玩家进 PLAY 后自动握手；本场景只等客户端就绪并断言客户端产品状态。
 */
public final class ProductHandshakeServerScenario extends ServerScenario {

    @Override
    public String suite() {
        return "acceptance";
    }

    @Override
    public String id() {
        return "product-handshake";
    }

    @Override
    public void run(ServerGameTestContext context) {
        awaitClientReady();
        runClientStep("verify-handshake", "{}");
    }
}
