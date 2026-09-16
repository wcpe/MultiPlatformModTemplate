package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/** 仅在 HYBRID（CatServer）矩阵执行的场景基类。 */
public abstract class HybridServerScenario extends BukkitServerScenario {

    private static final String MATRIX_PROPERTY = "mpmt.acceptance.matrix";

    @Override
    public final void run(ServerGameTestContext context) {
        if (!"HYBRID".equals(System.getProperty(MATRIX_PROPERTY))) {
            context.skip("仅 HYBRID 矩阵执行");
        }
        runHybrid(context);
    }

    protected abstract void runHybrid(ServerGameTestContext context);
}
