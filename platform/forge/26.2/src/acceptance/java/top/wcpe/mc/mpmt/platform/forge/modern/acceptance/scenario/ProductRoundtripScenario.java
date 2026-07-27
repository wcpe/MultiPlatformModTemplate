package top.wcpe.mc.mpmt.platform.forge.modern.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;

/** 让客户端通过产品 dispatcher 发 Ping 并验证产品服务端回 Pong。 */
public final class ProductRoundtripScenario extends ServerScenario {

    @Override
    public String suite() {
        return "acceptance";
    }

    @Override
    public String id() {
        return "product-roundtrip";
    }

    @Override
    public void run(ServerGameTestContext context) {
        awaitClientReady();
        runClientStep("verify-roundtrip", "{}");
    }
}
