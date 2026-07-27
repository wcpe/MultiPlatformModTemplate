package top.wcpe.mc.mpmt.platform.fabric.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTest;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRegistry;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRunner;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReportMetadata;
import top.wcpe.mc.mpmt.acceptance.report.MatrixAcceptanceReportV2;
import top.wcpe.mc.mpmt.acceptance.report.MatrixScenarioCatalog;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;
import top.wcpe.mc.mpmt.platform.fabric.gametest.scenario.RealServerScenarioCatalog;

/**
 * Fabric realserver 验收驱动引导。
 *
 * <p>双轨：默认 P1 REAL_REQUIRED（14 项）；声明 {@code -Dmpmt.acceptance.matrix=Rn} 时经 SPI 跑产品场景并装配严格 v2。
 */
public final class AcceptanceDriverBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");

    private static final String ACTIVATION_PROPERTY = "mpmt.acceptance";
    private static final String REPORT_PROPERTY = "mpmt.acceptance.report";
    private static final String DEADLINE_PROPERTY = "mpmt.acceptance.deadlineMs";
    private static final String COMMIT_PROPERTY = "mpmt.acceptance.commit";
    private static final String VERSION_PROPERTY = "mpmt.acceptance.version";
    private static final String MC_VERSION_PROPERTY = "mpmt.acceptance.mcVersion";
    private static final String SERVER_VERSION_PROPERTY = "mpmt.acceptance.serverVersion";
    private static final String PRODUCT_SHA_PROPERTY = "mpmt.acceptance.productJarSha256";
    private static final String PLATFORM = "fabric";
    private static final long DEFAULT_DEADLINE_MS = 660_000L;
    private static final long HALT_GRACE_MS = 8_000L;

    private AcceptanceDriverBootstrap() {
        // 工具类不实例化
    }

    /** 由 gametest 入口调用：仅当激活时挂接服务端启动事件。 */
    public static void register() {
        if (!"true".equals(System.getProperty(ACTIVATION_PROPERTY))) {
            return;
        }
        if (MatrixAcceptanceReportV2.matrixModeActive()) {
            try {
                MatrixAcceptanceReportV2.deleteOldReport();
                MatrixAcceptanceReportV2.validateRequiredProperties();
            } catch (Throwable throwable) {
                LOGGER.error(
                        "装配 Fabric 矩阵验收失败：{}",
                        MatrixAcceptanceReportV2.describe(throwable));
                // 仍挂启动事件，以便写失败报告并停服
            }
            LOGGER.info(
                    "realserver 验收驱动已激活（矩阵 {}）",
                    System.getProperty(MatrixAcceptanceReportV2.MATRIX_PROPERTY));
        } else {
            LOGGER.info("realserver 验收驱动已激活，待服务端启动（P1 REAL_REQUIRED + v2 报告）");
        }
        ServerLifecycleEvents.SERVER_STARTED.register(AcceptanceDriverBootstrap::onServerStarted);
    }

    private static void onServerStarted(MinecraftServer server) {
        FabricAcceptanceControlChannel channel = new FabricAcceptanceControlChannel(server);
        channel.register();
        AtomicBoolean finished = new AtomicBoolean(false);
        long deadlineMs = deadlineMs();

        if (MatrixAcceptanceReportV2.matrixModeActive()) {
            try {
                ServerGameTestRegistry registry = loadMatrixRegistry(channel);
                Thread driver =
                        new Thread(
                                () -> runMatrixAndReport(server, channel, registry, finished),
                                "mpmt-acceptance-driver");
                driver.setDaemon(true);
                driver.start();
                Thread watchdog =
                        new Thread(
                                () -> watchdogMatrix(server, channel, finished, deadlineMs),
                                "mpmt-acceptance-watchdog");
                watchdog.setDaemon(true);
                watchdog.start();
                LOGGER.info("Fabric 矩阵验收已就绪，SPI 场景 {}", registry.all().size());
            } catch (Throwable throwable) {
                finishOnce(
                        server,
                        finished,
                        MatrixAcceptanceReportV2.renderFailure(
                                channel.client(),
                                "装配 Fabric 矩阵场景失败："
                                        + MatrixAcceptanceReportV2.describe(throwable)));
            }
            return;
        }

        RealServerScenarioCatalog.assertMatchesMatrix();
        List<ServerGameTest> tests = RealServerScenarioCatalog.all();
        for (ServerGameTest test : tests) {
            if (test instanceof ServerScenario) {
                ((ServerScenario) test).bindClient(channel.client());
            }
        }

        Thread driver =
                new Thread(() -> runP1AndReport(server, tests, finished), "mpmt-acceptance-driver");
        driver.setDaemon(true);
        driver.start();
        Thread watchdog =
                new Thread(
                        () -> watchdogP1(server, finished, deadlineMs), "mpmt-acceptance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static ServerGameTestRegistry loadMatrixRegistry(
            FabricAcceptanceControlChannel channel) {
        String matrix = System.getProperty(MatrixAcceptanceReportV2.MATRIX_PROPERTY);
        ServerGameTestRegistry registry = new ServerGameTestRegistry();
        for (ServerScenario scenario :
                ServiceLoader.load(
                        ServerScenario.class, AcceptanceDriverBootstrap.class.getClassLoader())) {
            if (!MatrixScenarioCatalog.allowsInMatrix(matrix, scenario.id())) {
                continue;
            }
            scenario.bindClient(channel.client());
            registry.register(scenario);
        }
        if (registry.all().isEmpty()) {
            throw new IllegalStateException("未发现任何 Fabric 矩阵验收 SPI 场景");
        }
        return registry;
    }

    private static void runP1AndReport(
            MinecraftServer server, List<ServerGameTest> tests, AtomicBoolean finished) {
        List<ScenarioResult> results =
                ServerGameTestRunner.runAll(tests, test -> new FabricServerGameTestContext(server));
        List<String> scenarios = P1ScenarioMatrix.requiredFor(PLATFORM);
        String report = AcceptanceReport.render(metadata(scenarios), results);
        finishOnce(server, finished, report);
    }

    private static void runMatrixAndReport(
            MinecraftServer server,
            FabricAcceptanceControlChannel channel,
            ServerGameTestRegistry registry,
            AtomicBoolean finished) {
        try {
            List<ScenarioResult> results =
                    ServerGameTestRunner.runAll(
                            registry.all(), test -> new FabricServerGameTestContext(server));
            finishOnce(
                    server,
                    finished,
                    MatrixAcceptanceReportV2.renderReport(channel.client(), results));
        } catch (Throwable throwable) {
            finishOnce(
                    server,
                    finished,
                    MatrixAcceptanceReportV2.renderFailure(
                            channel.client(),
                            "执行 Fabric 矩阵验收失败："
                                    + MatrixAcceptanceReportV2.describe(throwable)));
        }
    }

    private static AcceptanceReportMetadata metadata(List<String> scenarios) {
        return new AcceptanceReportMetadata(
                property(COMMIT_PROPERTY),
                property(VERSION_PROPERTY),
                PLATFORM,
                property(MC_VERSION_PROPERTY),
                property(SERVER_VERSION_PROPERTY),
                property(PRODUCT_SHA_PROPERTY),
                scenarios);
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("缺少 realserver 报告元数据属性：" + name);
        }
        return value;
    }

    private static void watchdogP1(
            MinecraftServer server, AtomicBoolean finished, long deadlineMs) {
        try {
            Thread.sleep(deadlineMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        List<ScenarioResult> timeout =
                Collections.singletonList(
                        new ScenarioResult(
                                "framework",
                                "absolute-deadline",
                                ScenarioStatus.ERROR,
                                deadlineMs,
                                "验收绝对截止超时 " + deadlineMs + "ms"));
        finishOnce(server, finished, AcceptanceReport.render(timeout));
    }

    private static void watchdogMatrix(
            MinecraftServer server,
            FabricAcceptanceControlChannel channel,
            AtomicBoolean finished,
            long deadlineMs) {
        try {
            Thread.sleep(deadlineMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        AcceptanceClient client = channel == null ? null : channel.client();
        finishOnce(
                server,
                finished,
                MatrixAcceptanceReportV2.renderFailure(
                        client, "验收绝对截止超时 " + deadlineMs + "ms"));
    }

    private static void finishOnce(MinecraftServer server, AtomicBoolean finished, String report) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        writeReport(report);
        LOGGER.info("realserver 验收收尾，权威报告：\n{}", report);
        server.halt(false);
        startHardHalt();
    }

    private static void startHardHalt() {
        Thread hardHalt =
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
                        "mpmt-acceptance-halt");
        hardHalt.setDaemon(true);
        hardHalt.start();
    }

    private static long deadlineMs() {
        String raw = System.getProperty(DEADLINE_PROPERTY);
        if (raw == null) {
            return DEFAULT_DEADLINE_MS;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            LOGGER.warn("非法绝对截止 -D{}={}，回退默认 {}ms", DEADLINE_PROPERTY, raw, DEFAULT_DEADLINE_MS);
            return DEFAULT_DEADLINE_MS;
        }
    }

    private static void writeReport(String report) {
        String path =
                System.getProperty(
                        MatrixAcceptanceReportV2.matrixModeActive()
                                ? MatrixAcceptanceReportV2.REPORT_PROPERTY
                                : REPORT_PROPERTY);
        if (path == null) {
            LOGGER.warn("未指定报告路径（-D{}），跳过写文件", REPORT_PROPERTY);
            return;
        }
        try {
            java.nio.file.Path file = Paths.get(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, report.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.error("写验收报告失败：{}", path, e);
        }
    }
}
