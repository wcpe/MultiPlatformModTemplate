package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import java.util.concurrent.CountDownLatch;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.BukkitServerGameTestContext;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario.FoliaSchedulerScenarioSupport.PlayerTarget;

/** R6：从在线客户端取得世界坐标，并经产品实际区域调度入口执行任务。 */
public final class RegionSchedulerServerScenario extends R6ServerScenario {

    @Override
    public String id() {
        return "region-scheduler";
    }

    @Override
    protected void runR6(ServerGameTestContext context) {
        awaitClientReady(CLIENT_READY_TIMEOUT_MS);
        BukkitServerGameTestContext bukkit = bukkit(context);
        FoliaSchedulerScenarioSupport.assertFoliaScheduler(context);
        PlayerTarget target = FoliaSchedulerScenarioSupport.playerTarget(context, bukkit);
        CountDownLatch executed = new CountDownLatch(1);
        ProductPluginAccess.runLocationSchedulerTask(
                target.worldId(), target.blockX(), target.blockZ(), executed::countDown);
        FoliaSchedulerScenarioSupport.awaitTask(context, executed, "区域");
    }
}
