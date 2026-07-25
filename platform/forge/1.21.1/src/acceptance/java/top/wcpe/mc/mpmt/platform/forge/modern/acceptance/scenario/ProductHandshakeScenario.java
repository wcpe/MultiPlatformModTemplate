package top.wcpe.mc.mpmt.platform.forge.modern.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;

/** 验证客户端通过产品通道完成完整握手和标识上报。 */
public final class ProductHandshakeScenario extends ServerScenario {

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
