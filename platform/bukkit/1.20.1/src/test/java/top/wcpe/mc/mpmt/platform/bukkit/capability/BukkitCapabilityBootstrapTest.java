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
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.PlayerPort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.WorldPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionRegistry;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitChannels;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter;
import top.wcpe.mc.mpmt.platform.bukkit.version.SupportedVersion;

/**
 * Bukkit 平台能力示例桥接集成测试（MockBukkit，无需真实服，FR-23 / FR-26）。
 *
 * <p>验证"装配 capability bootstrap → 玩家进服触发桥接 → 经自有 EventBus 投递领域事件 →
 * L0 示例经 BukkitPersistencePort 持久化首次加入时间"。
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
        // 非 Folia：注入主线程调度适配器（测试 classpath 可能无 services）
        BukkitCapabilityBootstrap.register(
                plugin, runtime, capability -> false, new BukkitConnectionRegistry(), stubAdapter());
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
        BukkitCapabilityBootstrap.register(
                plugin, runtime, capability -> false, new BukkitConnectionRegistry(), stubAdapter());
        assertTrue(runtime.ports().contains(PlayerPort.class));
        assertTrue(runtime.ports().contains(WorldPort.class));

        UUID uuid = UUID.randomUUID();
        server.addPlayer(new PlayerMock(server, "Repeater", uuid));
        server.getScheduler().waitAsyncTasksFinished();

        Path file = dataFile(plugin);
        String key = "first-join:" + uuid;
        String firstValue = load(file).getProperty(key);

        server.addPlayer(new PlayerMock(server, "Repeater", uuid));
        server.getScheduler().waitAsyncTasksFinished();

        String secondValue = load(file).getProperty(key);
        assertEquals(firstValue, secondValue, "再次加入不应覆盖首次加入时间");
    }

    private static BukkitVersionAdapter stubAdapter() {
        return new BukkitVersionAdapter() {
            @Override
            public SupportedVersion version() {
                return SupportedVersion.V1_20;
            }

            @Override
            public BukkitChannels channels() {
                return new BukkitChannels("mpmt:main");
            }

            @Override
            public SchedulerPort createScheduler(Plugin plugin, boolean regionScheduler) {
                return new BukkitSchedulerPort(plugin);
            }

            @Override
            public void executeGlobal(Plugin plugin, Runnable task) {
                plugin.getServer().getScheduler().runTask(plugin, task);
            }
        };
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
