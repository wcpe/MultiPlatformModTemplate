package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/** 仅在 R6 Folia 矩阵执行的场景基类。 */
public abstract class R6ServerScenario extends BukkitServerScenario {

    private static final String MATRIX_PROPERTY = "mpmt.acceptance.matrix";

    @Override
    public final void run(ServerGameTestContext context) {
        if (!"R6".equals(System.getProperty(MATRIX_PROPERTY))) {
            context.skip("仅 R6 矩阵执行");
        }
        runR6(context);
    }

    protected abstract void runR6(ServerGameTestContext context);
}
