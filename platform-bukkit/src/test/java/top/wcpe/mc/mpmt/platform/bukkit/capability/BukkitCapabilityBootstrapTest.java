package top.wcpe.mc.mpmt.platform.bukkit.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.PlayerPort;
import top.wcpe.mc.mpmt.core.domain.port.WorldPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;

/**
 * Bukkit 平台能力示例桥接集成测试（MockBukkit，无需真实服，FR-23 / FR-26）。
 *
 * <p>验证"装配 capability bootstrap → 玩家进服触发桥接 → 经自有 EventBus 投递领域事件 →
 * L0 示例经 BukkitPersistencePort 持久化首次加入时间"。真实 Paper 的端到端场景为实机维度（用户确认）。
 *
 * <p>注：{@link top.wcpe.mc.mpmt.platform.spi.PlatformProvider} 是进程级静态 Holder，本用例不经产品插件
 * 主类入口（避免重复 boot 污染），用 MockBukkit 的临时 MockPlugin 承载数据目录 / 调度 / 监听器，
 * 直接校验 capability 桥接本身。
 */
class BukkitCapabilityBootstrapTest {

    @AfterEach
    void 拆除Mock() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    @DisplayName("玩家进服后：经桥接投递领域事件，L0 示例异步持久化首次加入时间")
    void 玩家进服后持久化首次加入时间() throws Exception {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();

        MpmtRuntime runtime = new MpmtRuntime();
        // 非 Folia 环境（无 REGION_SCHEDULER 能力）：调度退化为主线程
        BukkitCapabilityBootstrap.register(plugin, runtime, capability -> false);
        assertTrue(runtime.ports().contains(PlayerPort.class));
        assertTrue(runtime.ports().contains(WorldPort.class));

        PlayerMock player = server.addPlayer();

        // L0 onPlayerJoined 把持久化放到 runAsync：等异步任务跑完再断言落盘
        server.getScheduler().waitAsyncTasksFinished();

        Path file = dataFile(plugin);
        assertTrue(Files.exists(file), "首次加入应写入持久化文件");

        Properties properties = load(file);
        String key = "first-join:" + player.getUniqueId();
        assertTrue(properties.containsKey(key), "应记录该玩家的首次加入时间，键=" + key);
        assertFalse(properties.getProperty(key).isEmpty(), "首次加入时间值不应为空");
    }

    @Test
    @DisplayName("同一玩家再次进服：不覆盖首次加入时间（firstJoin 为 false 路径）")
    void 同一玩家再次进服不覆盖首次加入时间() throws Exception {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();

        MpmtRuntime runtime = new MpmtRuntime();
        // 非 Folia 环境（无 REGION_SCHEDULER 能力）：调度退化为主线程
        BukkitCapabilityBootstrap.register(plugin, runtime, capability -> false);
        assertTrue(runtime.ports().contains(PlayerPort.class));
        assertTrue(runtime.ports().contains(WorldPort.class));

        UUID uuid = UUID.randomUUID();
        server.addPlayer(new PlayerMock(server, "Repeater", uuid));
        server.getScheduler().waitAsyncTasksFinished();

        Path file = dataFile(plugin);
        String key = "first-join:" + uuid;
        String firstValue = load(file).getProperty(key);

        // 同一 UUID 再次加入：firstJoin 应判定为 false，不覆盖原值
        server.addPlayer(new PlayerMock(server, "Repeater", uuid));
        server.getScheduler().waitAsyncTasksFinished();

        String secondValue = load(file).getProperty(key);
        assertEquals(firstValue, secondValue, "再次加入不应覆盖首次加入时间");
    }

    private static Path dataFile(MockPlugin plugin) {
        return plugin.getDataFolder()
                .toPath()
                .resolve("data")
                .resolve("capability-example.properties");
    }

    private static Properties load(Path file) throws Exception {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
