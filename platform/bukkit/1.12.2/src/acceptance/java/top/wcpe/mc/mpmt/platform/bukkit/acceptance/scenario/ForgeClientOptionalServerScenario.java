package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/** HYBRID：由 Forge 1.12.2 客户端权威验证 client-only 与 optional 连接语义。 */
public final class ForgeClientOptionalServerScenario extends HybridServerScenario {

    @Override
    public String id() {
        return "forge-client-optional";
    }

    @Override
    protected void runHybrid(ServerGameTestContext context) {
        awaitClientReady(CLIENT_READY_TIMEOUT_MS);
        runClientStep("verify-optional", "{}");
    }
}
