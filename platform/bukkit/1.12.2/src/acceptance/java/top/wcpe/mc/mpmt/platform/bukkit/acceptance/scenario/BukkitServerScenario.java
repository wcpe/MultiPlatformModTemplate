package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.BukkitServerGameTestContext;

/** Bukkit realserver 场景的公共套件与上下文转换。 */
abstract class BukkitServerScenario extends ServerScenario {

    /** 与 {@link ServerScenario#DEFAULT_CLIENT_READY_TIMEOUT_MS} 对齐，覆盖慢机冷启动。 */
    protected static final long CLIENT_READY_TIMEOUT_MS = DEFAULT_CLIENT_READY_TIMEOUT_MS;

    @Override
    public final String suite() {
        return "acceptance";
    }

    protected final BukkitServerGameTestContext bukkit(ServerGameTestContext context) {
        if (!(context instanceof BukkitServerGameTestContext)) {
            throw new IllegalStateException("Bukkit 场景收到错误的执行上下文");
        }
        return (BukkitServerGameTestContext) context;
    }
}
