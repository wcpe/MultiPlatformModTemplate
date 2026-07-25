package top.wcpe.mc.mpmt.platform.sponge.acceptance;

import com.google.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRegistry;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRunner;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.MatrixAcceptanceReportV2;
import top.wcpe.mc.mpmt.acceptance.report.MatrixScenarioCatalog;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;
import top.wcpe.mc.mpmt.platform.sponge.acceptance.scenario.SpongeSmokeServerScenario;

/**
 * Sponge realserver 验收驱动：默认 SPI 全场景 + 简版报告；声明矩阵时过滤 smoke 并装配严格 v2。
 */
@Plugin("mpmt-acceptance")
public final class MpmtSpongeAcceptancePlugin {

    private static final String ACTIVATION_PROPERTY = "mpmt.acceptance";
    private static final String REPORT_PROPERTY = "mpmt.acceptance.report";
    private static final String DEADLINE_PROPERTY = "mpmt.acceptance.deadlineMs";
    private static final long DEFAULT_DEADLINE_MS = 300_000L;
    private static final long HALT_GRACE_MS = 8_000L;

    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final Logger logger;
    private final PluginContainer container;

    private SpongeAcceptanceControlChannel channel;
    private boolean matrixMode;

    @Inject
    MpmtSpongeAcceptancePlugin(final Logger logger, final PluginContainer container) {
        this.logger = logger;
        this.container = container;
    }

    @Listener
    public void onRegisterChannels(final RegisterChannelEvent event) {
        if (!activated()) {
            return;
        }
        RawDataChannel control =
                event.register(SpongeAcceptanceControlChannelId.CHANNEL, RawDataChannel.class);
        channel = new SpongeAcceptanceControlChannel(logger, container);
        channel.register(control);
    }

    @Listener
    public void onServerStarted(final StartedEngineEvent<Server> event) {
        if (!activated()) {
            logger.info("验收驱动未激活（-Dmpmt.acceptance=true 开启），插件空载");
            return;
        }
        matrixMode = MatrixAcceptanceReportV2.matrixModeActive();
        if (matrixMode) {
            try {
                MatrixAcceptanceReportV2.deleteOldReport();
                MatrixAcceptanceReportV2.validateRequiredProperties();
            } catch (Throwable throwable) {
                String detail = MatrixAcceptanceReportV2.describe(throwable);
                logger.error("装配 Sponge 矩阵验收失败：{}", detail);
                finishOnce(
                        MatrixAcceptanceReportV2.renderFailure(
                                acceptanceClient(), "装配 Sponge 矩阵验收失败：" + detail));
                return;
            }
        }

        ServerGameTestRegistry registry = loadSpiRegistry(matrixMode);
        long deadline = deadlineMs();
        Thread driver = new Thread(() -> runAndReport(registry), "mpmt-sponge-acceptance-driver");
        driver.setDaemon(true);
        driver.start();
        Thread watchdog = new Thread(() -> watchdog(deadline), "mpmt-sponge-acceptance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        logger.info(
                "realserver Sponge 验收驱动已激活，SPI 场景 {}（matrix={}）",
                registry.all().size(),
                matrixMode);
    }

    @Listener
    public void onDisconnect(final ServerSideConnectionEvent.Disconnect event) {
        if (channel != null) {
            channel.onClientDisconnected();
        }
    }

    private ServerGameTestRegistry loadSpiRegistry(boolean matrixOnly) {
        String matrix =
                matrixOnly
                        ? System.getProperty(MatrixAcceptanceReportV2.MATRIX_PROPERTY)
                        : null;
        ServerGameTestRegistry registry = new ServerGameTestRegistry();
        for (ServerScenario scenario :
                ServiceLoader.load(ServerScenario.class, getClass().getClassLoader())) {
            if (matrixOnly && !MatrixScenarioCatalog.allowsInMatrix(matrix, scenario.id())) {
                continue;
            }
            scenario.bindClient(channel.client());
            registry.register(scenario);
        }
        if (registry.all().isEmpty()) {
            throw new IllegalStateException("未发现任何 Sponge 验收 SPI 场景");
        }
        return registry;
    }

    private void runAndReport(ServerGameTestRegistry registry) {
        try {
            List<ScenarioResult> results =
                    ServerGameTestRunner.runAll(
                            registry.all(), test -> new SpongeServerGameTestContext(container));
            if (matrixMode) {
                finishOnce(
                        MatrixAcceptanceReportV2.renderReport(channel.client(), results));
            } else {
                finishOnce(AcceptanceReport.render(results));
            }
        } catch (Throwable throwable) {
            if (matrixMode) {
                finishOnce(
                        MatrixAcceptanceReportV2.renderFailure(
                                acceptanceClient(),
                                "执行 Sponge 矩阵验收失败："
                                        + MatrixAcceptanceReportV2.describe(throwable)));
            } else {
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
    }

    private void watchdog(long deadlineMs) {
        try {
            Thread.sleep(deadlineMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (matrixMode) {
            finishOnce(
                    MatrixAcceptanceReportV2.renderFailure(
                            acceptanceClient(), "验收绝对截止超时 " + deadlineMs + "ms"));
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
        finishOnce(AcceptanceReport.render(fallback));
    }

    private AcceptanceClient acceptanceClient() {
        return channel == null ? null : channel.client();
    }

    private void finishOnce(String report) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        writeReport(report);
        logger.info("realserver Sponge 验收收尾，权威报告：\n{}", report);
        Sponge.server().shutdown();
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
                        "mpmt-sponge-acceptance-halt");
        halt.setDaemon(true);
        halt.start();
    }

    private boolean activated() {
        return "true".equals(System.getProperty(ACTIVATION_PROPERTY));
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
                        matrixMode ? MatrixAcceptanceReportV2.REPORT_PROPERTY : REPORT_PROPERTY);
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        try {
            Path file = Paths.get(path);
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, report.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("写验收报告失败：{}", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private static final Class<?> SCENARIO_HINT = SpongeSmokeServerScenario.class;
}
