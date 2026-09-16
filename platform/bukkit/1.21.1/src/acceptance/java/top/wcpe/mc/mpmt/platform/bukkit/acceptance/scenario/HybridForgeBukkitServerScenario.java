package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/** HYBRID：断言产品 FeatureGate 实际启用 Forge+Bukkit 融合服能力。 */
public final class HybridForgeBukkitServerScenario extends HybridServerScenario {

    @Override
    public String id() {
        return "hybrid-forge-bukkit";
    }

    @Override
    protected void runHybrid(ServerGameTestContext context) {
        context.assertTrue(
                ProductPluginAccess.isHybridForgeBukkit(),
                "产品 FeatureGate 未启用 Forge+Bukkit 融合服能力");
    }
}
