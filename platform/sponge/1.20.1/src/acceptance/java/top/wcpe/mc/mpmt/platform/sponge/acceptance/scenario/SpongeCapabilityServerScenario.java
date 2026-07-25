package top.wcpe.mc.mpmt.platform.sponge.acceptance.scenario;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.platform.sponge.MpmtSpongePlugin;
import top.wcpe.mc.mpmt.platform.sponge.capability.SpongeDataDirectoryPort;
import top.wcpe.mc.mpmt.platform.sponge.capability.SpongePersistencePort;

/** Sponge 平台能力 realserver 场景：验证加入事件触发异步持久化，并向真实客户端发送欢迎消息。 */
public final class SpongeCapabilityServerScenario extends ServerScenario {

    private static final int PERSISTENCE_TIMEOUT_TICKS = 100;
    private static final String NAMESPACE = "capability-example";
    private static final String FIRST_JOIN_KEY_PREFIX = "first-join:";
    private static final String DATA_FILE = "capability-example.properties";

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
        // 使用基类默认 15 分钟：Fabric 客户端冷启在慢机上常超过 3 分钟
        awaitClientReady();
        MpmtSpongePlugin plugin = SpongeScenarioSupport.productPlugin(context);
        UUID playerId = context.onMain(() -> SpongeScenarioSupport.onlinePlayerId(context));
        Path configDirectory = plugin.dataDirectory();
        SpongePersistencePort persistence =
                new SpongePersistencePort(new SpongeDataDirectoryPort(configDirectory));
        boolean persisted = awaitPersisted(context, persistence, playerId);
        context.assertTrue(persisted, "玩家加入后 capability 示例应异步持久化首次加入时间");
        Path dataFile = configDirectory.resolve("data").resolve(DATA_FILE);
        context.assertTrue(Files.exists(dataFile), "capability 持久化文件不存在：" + dataFile);
        runClientStep("capability-message", "{}");
    }

    private static boolean awaitPersisted(
            ServerGameTestContext context, SpongePersistencePort persistence, UUID playerId) {
        String key = FIRST_JOIN_KEY_PREFIX + playerId;
        for (int tick = 0; tick < PERSISTENCE_TIMEOUT_TICKS; tick++) {
            if (persistence.read(NAMESPACE, key).isPresent()) {
                return true;
            }
            context.waitTicks(1);
        }
        return false;
    }
}
