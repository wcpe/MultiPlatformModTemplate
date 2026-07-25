package top.wcpe.mc.mpmt.platform.bukkit.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTest;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRegistry;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRunner;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.MatrixAcceptanceReportV2;
import top.wcpe.mc.mpmt.acceptance.report.MatrixScenarioCatalog;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario.BukkitRealRoundTripServerScenario;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.sim.BukkitP1Simulation;

/**
 * Bukkit realserver 验收驱动插件（仅验收运行期用，非产品插件，ADR-0014）：仅当 {@code -Dmpmt.acceptance=true}
 * 激活。
 *
 * <p>双轨：
 *
 * <ul>
 *   <li><b>P1（默认）</b>：13 项进程内回环 + {@code real-round-trip}，输出 tip 既有 acceptance 报告。
 *   <li><b>矩阵 R1–R6</b>：声明 {@code -Dmpmt.acceptance.matrix=Rn} 时，经 ServiceLoader 仅装载
 *       {@link MatrixScenarioCatalog} required 场景并装配严格 v2 报告。
 * </ul>
 *
 * <p>「客户端」复用我方 Fabric 验收伴侣连入真实 Paper 服（异构互通，FR-11②）。
 */
public final class MpmtBukkitAcceptancePlugin extends JavaPlugin implements Listener {

    private static final String ACTIVATION_PROPERTY = "mpmt.acceptance";
    private static final String REPORT_PROPERTY = "mpmt.acceptance.report";
    private static final String DEADLINE_PROPERTY = "mpmt.acceptance.deadlineMs";
    private static final String PLATFORM_PROPERTY = "mpmt.acceptance.platform";
    private static final long DEFAULT_DEADLINE_MS = 660_000L;
    private static final long HALT_GRACE_MS = 8_000L;
    /** 1.12.2 产品通道为 4 字符 ASCII「MPMT」（见 V1_12BukkitVersionAdapter），非现代 mpmt:main。 */
    private static final String PRODUCT_CHANNEL = "MPMT";

    private final AtomicBoolean finished = new AtomicBoolean(false);

    private BukkitAcceptanceControlChannel channel;
    private boolean matrixMode;

    @Override
    public void onEnable() {
        if (!"true".equals(System.getProperty(ACTIVATION_PROPERTY))) {
            getLogger().info("验收驱动未激活（-Dmpmt.acceptance=true 开启），插件空载");
            return;
        }
        matrixMode = BukkitAcceptanceReportV2.matrixModeActive();
        if (matrixMode) {
            enableMatrixMode();
        } else {
            enableP1Mode();
        }
    }

    /** P1：保留 tip 既有 13 回环 + real-round-trip。 */
    private void enableP1Mode() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, PRODUCT_CHANNEL);
        channel = new BukkitAcceptanceControlChannel(this);
        channel.register();
        getServer().getPluginManager().registerEvents(this, this);

        ServerScenario roundTrip = new BukkitRealRoundTripServerScenario();
        roundTrip.bindClient(channel.client());

        long deadline = deadlineMs();
        Thread driver = new Thread(() -> runP1AndReport(roundTrip), "mpmt-bukkit-acceptance-driver");
        driver.setDaemon(true);
        driver.start();
        Thread watchdog = new Thread(() -> watchdogP1(deadline), "mpmt-bukkit-acceptance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        getLogger().info("realserver Bukkit 验收驱动已激活（P1：13 回环 + real-round-trip）");
    }

    /** 矩阵：ServiceLoader 场景 + 严格 v2 元数据/制品校验。 */
    private void enableMatrixMode() {
        try {
            BukkitAcceptanceReportV2.deleteOldReport();
            BukkitAcceptanceReportV2.validateRequiredProperties();
            getServer().getMessenger().registerOutgoingPluginChannel(this, PRODUCT_CHANNEL);
            channel = new BukkitAcceptanceControlChannel(this);
            channel.register();
            getServer().getPluginManager().registerEvents(this, this);
            ServerGameTestRegistry registry = loadSpiRegistry();
            startMatrixDrivers(registry);
            getLogger()
                    .info(
                            "realserver Bukkit 验收驱动已激活（矩阵 "
                                    + System.getProperty(BukkitAcceptanceReportV2.MATRIX_PROPERTY)
                                    + "，SPI 场景 "
                                    + registry.all().size()
                                    + "）");
        } catch (Throwable throwable) {
            String detail = BukkitAcceptanceReportV2.describe(throwable);
            getLogger().severe("装配 Bukkit 矩阵验收驱动失败：" + detail);
            finishOnce(
                    BukkitAcceptanceReportV2.renderFailure(
                            acceptanceClient(), "装配 Bukkit 矩阵验收驱动失败：" + detail));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (channel != null) {
            channel.onClientDisconnected();
        }
    }

    private ServerGameTestRegistry loadSpiRegistry() {
        String matrix = System.getProperty(MatrixAcceptanceReportV2.MATRIX_PROPERTY);
        ServerGameTestRegistry registry = new ServerGameTestRegistry();
        for (ServerScenario scenario :
                ServiceLoader.load(ServerScenario.class, getClass().getClassLoader())) {
            // 仅装载本矩阵 required；P1 real-round-trip / 他矩阵专属场景不进本轮报告
            if (!MatrixScenarioCatalog.allowsInMatrix(matrix, scenario.id())) {
                continue;
            }
            scenario.bindClient(channel.client());
            registry.register(scenario);
        }
        if (registry.all().isEmpty()) {
            throw new IllegalStateException("未发现任何 Bukkit 矩阵验收 SPI 场景");
        }
        return registry;
    }

    private void startMatrixDrivers(ServerGameTestRegistry registry) {
        long deadline = deadlineMs();
        Thread driver =
                new Thread(() -> runMatrixAndReport(registry), "mpmt-bukkit-acceptance-driver");
        driver.setDaemon(true);
        driver.start();
        Thread watchdog =
                new Thread(() -> watchdogMatrix(deadline), "mpmt-bukkit-acceptance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void runP1AndReport(ServerScenario roundTrip) {
        try {
            List<ScenarioResult> results = new ArrayList<>(BukkitP1Simulation.runLoopbackCore());
            List<ServerGameTest> live = Collections.singletonList(roundTrip);
            results.addAll(
                    ServerGameTestRunner.runAll(
                            live, test -> new BukkitServerGameTestContext(this)));
            String platform = System.getProperty(PLATFORM_PROPERTY, "bukkit");
            List<String> scenarios = P1ScenarioMatrix.requiredFor(platform);
            assertCatalogMatches(scenarios, results);
            String report = AcceptanceReport.render(BukkitP1Simulation.metadata(platform), results);
            finishOnce(report);
        } catch (Throwable throwable) {
            getLogger().severe("P1 验收驱动失败：" + throwable.getMessage());
            List<ScenarioResult> fallback =
                    Collections.singletonList(
                            new ScenarioResult(
                                    "framework",
                                    "driver-error",
                                    ScenarioStatus.ERROR,
                                    0L,
                                    String.valueOf(throwable.getMessage())));
            finishOnce(AcceptanceReport.render(fallback));
        }
    }

    private void runMatrixAndReport(ServerGameTestRegistry registry) {
        try {
            List<ScenarioResult> results =
                    ServerGameTestRunner.runAll(
                            registry.all(), test -> new BukkitServerGameTestContext(this));
            finishOnce(BukkitAcceptanceReportV2.renderReport(channel.client(), results));
        } catch (Throwable throwable) {
            finishOnce(
                    BukkitAcceptanceReportV2.renderFailure(
                            acceptanceClient(),
                            "执行 Bukkit 矩阵验收驱动失败："
                                    + BukkitAcceptanceReportV2.describe(throwable)));
        }
    }

    private static void assertCatalogMatches(List<String> required, List<ScenarioResult> results) {
        List<String> actual = new ArrayList<>();
        for (ScenarioResult result : results) {
            actual.add(result.getSuite() + "/" + result.getId());
        }
        if (!required.equals(actual)) {
            throw new IllegalStateException(
                    "Bukkit realserver 场景与 P1 矩阵不一致：actual=" + actual + " matrix=" + required);
        }
    }

    private void watchdogP1(long deadlineMs) {
        try {
            Thread.sleep(deadlineMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        List<ScenarioResult> fallback =
                Collections.singletonList(
                        new ScenarioResult(
                                "framework",
                                "absolute-deadline",
                                ScenarioStatus.ERROR,
                                deadlineMs,
                                "验收绝对截止超时未收尾"));
        // 看门狗 fallback 仍走 v1，避免缺元数据再抛；正常路径必须是 v2
        finishOnce(AcceptanceReport.render(fallback));
    }

    private void watchdogMatrix(long deadlineMs) {
        try {
            Thread.sleep(deadlineMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        finishOnce(
                BukkitAcceptanceReportV2.renderFailure(
                        acceptanceClient(), "验收绝对截止超时 " + deadlineMs + "ms"));
    }

    private AcceptanceClient acceptanceClient() {
        return channel == null ? null : channel.client();
    }

    private void finishOnce(String report) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        writeReport(report);
        getLogger().info("realserver Bukkit 验收收尾，权威报告：\n" + report);
        getServer().shutdown();
        startHardHalt();
    }

    private void startHardHalt() {
        Thread halt =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(HALT_GRACE_MS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            Runtime.getRuntime().halt(0);
                        },
                        "mpmt-bukkit-acceptance-halt");
        halt.setDaemon(true);
        halt.start();
    }

    private long deadlineMs() {
        String value = System.getProperty(DEADLINE_PROPERTY);
        if (value == null) {
            return DEFAULT_DEADLINE_MS;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_DEADLINE_MS;
        }
    }

    private void writeReport(String report) {
        String path =
                System.getProperty(
                        matrixMode
                                ? BukkitAcceptanceReportV2.REPORT_PROPERTY
                                : REPORT_PROPERTY);
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        try {
            Path file = Paths.get(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, report.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            getLogger().warning("写验收报告失败：" + e.getMessage());
        }
    }

    /** 编译期占位：确保真往返场景类打入验收 jar。 */
    @SuppressWarnings("unused")
    private static final Class<?> SCENARIO_HINT = BukkitRealRoundTripServerScenario.class;
}
