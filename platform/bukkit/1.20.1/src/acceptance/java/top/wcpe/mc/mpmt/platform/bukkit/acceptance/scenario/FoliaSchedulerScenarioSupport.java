package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.BukkitServerGameTestContext;

/**
 * R6 场景共享：经 {@link ProductPluginAccess} 调用产品 Folia 调度入口、在线玩家引用与有界等待。
 *
 * <p>验收 jar 不挂产品 main（ADR-0014），调度经 primitive 反射桥，不跨 CL 强转 SchedulerPort。
 */
final class FoliaSchedulerScenarioSupport {

    private static final String FOLIA_SCHEDULER_CLASS =
            "top.wcpe.mc.mpmt.platform.bukkit.capability.FoliaSchedulerPort";
    private static final long TASK_TIMEOUT_SECONDS = 10L;

    private FoliaSchedulerScenarioSupport() {
        // 工具类不实例化
    }

    /** 断言产品实际调度端口为 FoliaSchedulerPort。 */
    static void assertFoliaScheduler(ServerGameTestContext context) {
        context.assertEquals(
                FOLIA_SCHEDULER_CLASS,
                ProductPluginAccess.schedulerPortClassName(),
                "产品实际调度端口不是 FoliaSchedulerPort");
    }

    static PlayerTarget playerTarget(
            ServerGameTestContext context, BukkitServerGameTestContext bukkit) {
        return context.onMain(() -> captureTarget(context, bukkit));
    }

    static void awaitTask(
            ServerGameTestContext context, CountDownLatch latch, String operation) {
        try {
            if (!latch.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                context.fail(operation + " 调度任务未在限定时间内执行");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.fail("等待 " + operation + " 调度任务时被中断");
        }
    }

    private static PlayerTarget captureTarget(
            ServerGameTestContext context, BukkitServerGameTestContext bukkit) {
        Collection<? extends Player> players = bukkit.server().getOnlinePlayers();
        if (players.isEmpty()) {
            context.fail("无在线客户端玩家，无法取得 Folia 调度归属");
            return null;
        }
        Player player = players.iterator().next();
        Location location = player.getLocation();
        return new PlayerTarget(
                player.getUniqueId(),
                player.getWorld().getName(),
                location.getBlockX(),
                location.getBlockZ());
    }

    static final class PlayerTarget {

        private final UUID entityId;
        private final String worldId;
        private final int blockX;
        private final int blockZ;

        PlayerTarget(UUID entityId, String worldId, int blockX, int blockZ) {
            this.entityId = entityId;
            this.worldId = worldId;
            this.blockX = blockX;
            this.blockZ = blockZ;
        }

        UUID entityId() {
            return entityId;
        }

        String worldId() {
            return worldId;
        }

        int blockX() {
            return blockX;
        }

        int blockZ() {
            return blockZ;
        }
    }
}
