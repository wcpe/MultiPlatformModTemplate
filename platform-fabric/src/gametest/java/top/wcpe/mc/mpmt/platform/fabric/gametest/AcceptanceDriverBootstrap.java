package top.wcpe.mc.mpmt.platform.fabric.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTest;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRunner;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReportMetadata;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;
import top.wcpe.mc.mpmt.platform.fabric.gametest.scenario.RealServerScenarioCatalog;

/**
 * Fabric realserver 验收驱动引导：{@code -Dmpmt.acceptance=true} 激活后跑完整 P1 REAL_REQUIRED（14 项），
 * 输出 acceptance v2 权威报告。进程内回环场景不依赖客户端；{@code real-round-trip} 等客户端连入。
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
        ServerLifecycleEvents.SERVER_STARTED.register(AcceptanceDriverBootstrap::onServerStarted);
        LOGGER.info("realserver 验收驱动已激活，待服务端启动（P1 REAL_REQUIRED + v2 报告）");
    }

    private static void onServerStarted(MinecraftServer server) {
        FabricAcceptanceControlChannel channel = new FabricAcceptanceControlChannel(server);
        channel.register();

        RealServerScenarioCatalog.assertMatchesMatrix();
        List<ServerGameTest> tests = RealServerScenarioCatalog.all();
        for (ServerGameTest test : tests) {
            if (test instanceof ServerScenario) {
                ((ServerScenario) test).bindClient(channel.client());
            }
        }

        AtomicBoolean finished = new AtomicBoolean(false);

        Thread driver = new Thread(() -> runAndReport(server, tests, finished), "mpmt-acceptance-driver");
        driver.setDaemon(true);
        driver.start();

        long deadlineMs = deadlineMs();
        Thread watchdog = new Thread(() -> watchdog(server, finished, deadlineMs), "mpmt-acceptance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void runAndReport(
            MinecraftServer server, List<ServerGameTest> tests, AtomicBoolean finished) {
        List<ScenarioResult> results =
                ServerGameTestRunner.runAll(tests, test -> new FabricServerGameTestContext(server));
        List<String> scenarios = P1ScenarioMatrix.requiredFor(PLATFORM);
        String report = AcceptanceReport.render(metadata(scenarios), results);
        finishOnce(server, finished, report);
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

    private static void watchdog(MinecraftServer server, AtomicBoolean finished, long deadlineMs) {
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
        // 看门狗 fallback 仍走 v1 渲染避免缺元数据再抛；正常路径必须是 v2
        finishOnce(server, finished, AcceptanceReport.render(timeout));
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
        String path = System.getProperty(REPORT_PROPERTY);
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
