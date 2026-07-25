package top.wcpe.mc.mpmt.platform.fabric.gametest.scenario;

import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.platform.fabric.capability.FabricDataDirectoryPort;
import top.wcpe.mc.mpmt.platform.fabric.capability.FabricPersistencePort;
import top.wcpe.mc.mpmt.platform.fabric.gametest.FabricServerGameTestContext;

/**
 * 平台能力示例验收场景（realserver，FR-26）：验证"玩家加入 → 桥接领域事件 → L0 capability 示例经 L3 端口
 * 异步持久化首次加入时间"在真实 Fabric 服务端运行期成立。
 *
 * <p>玩家进世界后，断言 {@link FabricPersistencePort} 中已写入该玩家的 first-join 记录（capability 示例的副作用）。
 * 这是脚手架"一份 L0 逻辑经端口在真实平台一致运行"的端到端实机验证。
 */
public final class CapabilityServerScenario extends ServerScenario {

    /** 与 L0 capability 示例约定一致（PlatformCapabilityExample.NAMESPACE / FIRST_JOIN_KEY_PREFIX）。 */
    private static final String NAMESPACE = "capability-example";
    private static final String FIRST_JOIN_KEY_PREFIX = "first-join:";

    @Override
    public String suite() {
        return "acceptance";
    }

    @Override
    public String id() {
        return "capability-first-join";
    }

    @Override
    public void run(ServerGameTestContext context) {
        // 客户端冷启动（Gradle + 资源加载）常超 3 分钟，与绝对截止对齐放宽
        awaitClientReady(360_000L);

        // 取已连入玩家的 UUID（主线程读）
        UUID uuid =
                context.onMain(
                        () -> {
                            List<ServerPlayer> players =
                                    ((FabricServerGameTestContext) context).server().getPlayerList().getPlayers();
                            if (players.isEmpty()) {
                                context.fail("无在线玩家");
                            }
                            return players.get(0).getUUID();
                        });

        // 等 capability 示例异步持久化首次加入时间（runAsync 写文件）
        FabricPersistencePort persistence = new FabricPersistencePort(new FabricDataDirectoryPort());
        boolean persisted =
                context.awaitUntil(
                        100,
                        () -> persistence.read(NAMESPACE, FIRST_JOIN_KEY_PREFIX + uuid).isPresent());
        context.assertTrue(persisted, "玩家加入后 capability 示例应异步持久化首次加入时间");
    }
}
