package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import java.util.concurrent.CountDownLatch;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/** SCHEDULER：经产品实际 FoliaSchedulerPort 验证全局调度入口。 */
public final class GlobalSchedulerServerScenario extends SchedulerServerScenario {

    @Override
    public String id() {
        return "global-scheduler";
    }

    @Override
    protected void runScheduler(ServerGameTestContext context) {
        FoliaSchedulerScenarioSupport.assertFoliaScheduler(context);
        CountDownLatch executed = new CountDownLatch(1);
        ProductPluginAccess.runGlobalSchedulerTask(executed::countDown);
        FoliaSchedulerScenarioSupport.awaitTask(context, executed, "全局");
    }
}
