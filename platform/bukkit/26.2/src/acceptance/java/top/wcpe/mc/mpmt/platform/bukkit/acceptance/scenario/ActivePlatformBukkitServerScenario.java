package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/** R5：断言产品实际唯一活动平台为 Bukkit。 */
public final class ActivePlatformBukkitServerScenario extends R5ServerScenario {

    @Override
    public String id() {
        return "active-platform-bukkit";
    }

    @Override
    protected void runR5(ServerGameTestContext context) {
        context.assertEquals(
                "bukkit",
                ProductPluginAccess.activePlatformId(),
                "产品活动平台不是 Bukkit");
    }
}
