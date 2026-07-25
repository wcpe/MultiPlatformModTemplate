package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/** 仅在 R5 CatServer 矩阵执行的场景基类。 */
public abstract class R5ServerScenario extends BukkitServerScenario {

    private static final String MATRIX_PROPERTY = "mpmt.acceptance.matrix";

    @Override
    public final void run(ServerGameTestContext context) {
        if (!"R5".equals(System.getProperty(MATRIX_PROPERTY))) {
            context.skip("仅 R5 矩阵执行");
        }
        runR5(context);
    }

    protected abstract void runR5(ServerGameTestContext context);
}
