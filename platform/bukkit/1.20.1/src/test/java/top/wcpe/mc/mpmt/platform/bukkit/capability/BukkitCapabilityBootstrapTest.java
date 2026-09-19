package top.wcpe.mc.mpmt.platform.bukkit.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
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
 *
 * <p>等待一律经 {@link #等待异步链完成}：L0 的持久化在 runAsync 里，其末尾还会经 runForEntity
 * 再排一个主线程任务，单靠 MockBukkit 的 waitAsyncTasksFinished 不足以等到（见该方法说明）。
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
        String key = "first-join:" + player.getUniqueId();
        Path file = dataFile(plugin);

        // L0 onPlayerJoined 把持久化放到 runAsync：等该异步链把记录真正落盘
        等待异步链完成(server, () -> 已记录首次加入(file, key));

        assertTrue(Files.exists(file), "首次加入应写入持久化文件");
        Properties properties = load(file);
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
        PlayerMock first = new PlayerMock(server, "Repeater", uuid);
        server.addPlayer(first);
        String key = "first-join:" + uuid;
        Path file = dataFile(plugin);
        等待异步链完成(server, () -> 已记录首次加入(file, key));

        String firstValue = load(file).getProperty(key);
        assertFalse(firstValue == null || firstValue.isEmpty(), "首次加入应落盘，键=" + key);

        // 再次加入走 firstJoin 为 false 分支：不写盘，只在异步链末尾按归属发一条消息。
        // 该消息入队即证明整条异步链（读 → 判定非首次 → runForEntity 发消息）已跑完，
        // 比"等固定时长"确定得多。
        // nextComponentMessage 是破坏性 poll，故先把首次加入可能已入队的欢迎语排空，
        // 否则残留旧消息会让等待条件立即成立、退化成假通过。
        PlayerMock second = new PlayerMock(server, "Repeater", uuid);
        排空消息(first);
        排空消息(second);

        server.addPlayer(second);
        // 同 UUID 二次 addPlayer 会新增实例，而 Bukkit.getPlayer(uuid) 在 onlinePlayers 集合中
        // 取首个匹配、顺序不保证，故两个实例都作为信号来源。
        等待异步链完成(server, () -> 有消息(first) || 有消息(second));

        String secondValue = load(file).getProperty(key);
        assertEquals(firstValue, secondValue, "再次加入不应覆盖首次加入时间");
    }

    /**
     * 有界轮询等待异步链完成。
     *
     * <p>不能只用一次 {@code waitAsyncTasksFinished()}：MockBukkit 3.88.1 以
     * {@code ThreadPoolExecutor.getActiveCount()}（JDK 文档明示为"近似值"）判断异步是否结束，
     * 任务从提交到被计入活跃之间存在窗口，单次调用可能提前返回；而该方法等待期间不再推进 tick，
     * 异步任务内部经 runForEntity 排出的主线程任务将永不执行（MockBukkit issue #1610）。
     * 故此处反复"推 tick + 等异步"，直到条件成立或超时。
     */
    private static void 等待异步链完成(ServerMock server, BooleanSupplier satisfied) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            推进调度(server, 2);
            if (satisfied.getAsBoolean()) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("等待异步链完成超时（10 秒）");
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待异步链完成时被中断", e);
            }
        }
    }

    /** 推进调度若干 tick，并等待此刻在跑的真实异步任务结束。 */
    private static void 推进调度(ServerMock server, int ticks) {
        server.getScheduler().performTicks(ticks);
        server.getScheduler().waitAsyncTasksFinished();
    }

    /** 持久化文件里是否已写入该键（非空值）；异步写盘途中读失败按"尚未完成"处理，留待下一轮重试。 */
    private static boolean 已记录首次加入(Path file, String key) {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            String value = load(file).getProperty(key);
            return value != null && !value.isEmpty();
        } catch (IOException e) {
            // 异步写盘途中文件可能短暂不可读（截断 / 竞争）：视为"尚未完成"，留待下一轮重试。
            // 不捕获更宽的异常，避免把真实缺陷伪装成等待超时。
            return false;
        }
    }

    /** 该玩家队列里是否还有未取消息（nextComponentMessage 为破坏性 poll）。 */
    private static boolean 有消息(PlayerMock player) {
        return player.nextComponentMessage() != null;
    }

    /**
     * 排空该玩家已入队的消息，避免前一次进服的残留欢迎语污染后续等待条件。
     *
     * <p>取用与判空都落在 do-while 的循环条件里，循环体本身无语句——
     * 这样既是"排空"的最小写法，也不会触发 PMD 的空控制语句规则。
     */
    private static void 排空消息(PlayerMock player) {
        boolean drained;
        do {
            drained = 有消息(player);
        } while (drained);
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

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
