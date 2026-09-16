package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/** 仅在 SCHEDULER（Folia）矩阵执行的场景基类。 */
public abstract class SchedulerServerScenario extends BukkitServerScenario {

    private static final String MATRIX_PROPERTY = "mpmt.acceptance.matrix";

    @Override
    public final void run(ServerGameTestContext context) {
        if (!"SCHEDULER".equals(System.getProperty(MATRIX_PROPERTY))) {
            context.skip("仅 SCHEDULER 矩阵执行");
        }
        runScheduler(context);
    }

    protected abstract void runScheduler(ServerGameTestContext context);
}
