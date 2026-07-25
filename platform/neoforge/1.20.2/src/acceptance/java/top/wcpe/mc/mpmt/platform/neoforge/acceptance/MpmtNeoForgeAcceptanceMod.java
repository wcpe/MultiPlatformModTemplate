package top.wcpe.mc.mpmt.platform.neoforge.acceptance;

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
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.client.NeoForgeAcceptanceClientInit;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.scenario.NeoForgeRealRoundTripServerScenario;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.sim.NeoForgeP1Simulation;

/**
 * NeoForge realserver 验收驱动 mod（仅验收运行期用，非产品 mod，ADR-0014）：仅当 {@code -Dmpmt.acceptance=true}
 * 激活。
 *
 * <p>双轨：
 *
 * <ul>
 *   <li><b>P1（默认）</b>：13 项进程内回环 + {@code real-round-trip}。
 *   <li><b>矩阵 R1–R6</b>：{@code -Dmpmt.acceptance.matrix=Rn} 时经 ServiceLoader 跑 SPI 产品场景并装配严格
 *       v2（排除 real-round-trip）。
 * </ul>
 */
@Mod("mpmt_acceptance")
public final class MpmtNeoForgeAcceptanceMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");

    private static final String ACTIVATION_PROPERTY = "mpmt.acceptance";
    private static final String REPORT_PROPERTY = "mpmt.acceptance.report";
    private static final String DEADLINE_PROPERTY = "mpmt.acceptance.deadlineMs";
    private static final String PLATFORM = "neoforge";
    private static final long DEFAULT_DEADLINE_MS = 660_000L;
    private static final long HALT_GRACE_MS = 8_000L;

    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final boolean activated;
    private final boolean matrixMode;
    private final NeoForgeAcceptanceControlChannel channel;

    public MpmtNeoForgeAcceptanceMod() {
        this.activated = "true".equals(System.getProperty(ACTIVATION_PROPERTY));
        this.matrixMode = activated && MatrixAcceptanceReportV2.matrixModeActive();
        if (!activated) {
            this.channel = null;
            LOGGER.info("验收驱动未激活（-Dmpmt.acceptance=true 开启），mod 空载");
            return;
        }
        if (matrixMode) {
            try {
                MatrixAcceptanceReportV2.deleteOldReport();
                MatrixAcceptanceReportV2.validateRequiredProperties();
            } catch (Throwable throwable) {
                this.channel = null;
                String detail = MatrixAcceptanceReportV2.describe(throwable);
                LOGGER.error("装配 NeoForge 矩阵验收失败：{}", detail);
                finishOnce(
                        null,
                        MatrixAcceptanceReportV2.renderFailure(
                                null, "装配 NeoForge 矩阵验收失败：" + detail));
                return;
            }
        }
        this.channel = new NeoForgeAcceptanceControlChannel();
        NeoForge.EVENT_BUS.register(this);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForgeAcceptanceClientInit.activate(channel);
        }
        LOGGER.info(
                matrixMode
                        ? "realserver NeoForge 验收驱动已激活（矩阵 {}）"
                        : "realserver NeoForge 验收驱动已激活，待服务端启动（P1 REAL_REQUIRED）",
                System.getProperty(MatrixAcceptanceReportV2.MATRIX_PROPERTY));
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!activated || channel == null) {
            return;
        }
        MinecraftServer server = event.getServer();
        channel.bindServer(server);
        long deadline = deadlineMs();
        if (matrixMode) {
            ServerGameTestRegistry registry = loadSpiRegistry();
            Thread driver =
                    new Thread(
                            () -> runMatrixAndReport(server, registry),
                            "mpmt-neoforge-acceptance-driver");
            driver.setDaemon(true);
            driver.start();
            Thread watchdog =
                    new Thread(() -> watchdogMatrix(deadline), "mpmt-neoforge-acceptance-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            LOGGER.info("realserver NeoForge 矩阵验收已就绪，SPI 场景 {}", registry.all().size());
            return;
        }
        ServerScenario roundTrip = new NeoForgeRealRoundTripServerScenario();
        roundTrip.bindClient(channel.client());
        Thread driver =
                new Thread(() -> runP1AndReport(server, roundTrip), "mpmt-neoforge-acceptance-driver");
        driver.setDaemon(true);
        driver.start();
        Thread watchdog = new Thread(() -> watchdogP1(deadline), "mpmt-neoforge-acceptance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        LOGGER.info("realserver NeoForge 验收驱动已就绪（13 回环 + real-round-trip 等客户端）");
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        NeoForgeAcceptanceControlChannel current = channel;
        if (current != null) {
            current.onClientDisconnected();
        }
    }

    private ServerGameTestRegistry loadSpiRegistry() {
        String matrix = System.getProperty(MatrixAcceptanceReportV2.MATRIX_PROPERTY);
        ServerGameTestRegistry registry = new ServerGameTestRegistry();
        for (ServerScenario scenario :
                ServiceLoader.load(ServerScenario.class, getClass().getClassLoader())) {
            if (!MatrixScenarioCatalog.allowsInMatrix(matrix, scenario.id())) {
                continue;
            }
            scenario.bindClient(channel.client());
            registry.register(scenario);
        }
        if (registry.all().isEmpty()) {
            throw new IllegalStateException("未发现任何 NeoForge 矩阵验收 SPI 场景");
        }
        return registry;
    }

    private void runP1AndReport(MinecraftServer server, ServerScenario roundTrip) {
        try {
            List<ScenarioResult> results = new ArrayList<>(NeoForgeP1Simulation.runLoopbackCore());
            List<ServerGameTest> live = Collections.singletonList(roundTrip);
            results.addAll(
                    ServerGameTestRunner.runAll(
                            live, test -> new NeoForgeServerGameTestContext(server)));
            List<String> scenarios = P1ScenarioMatrix.requiredFor(PLATFORM);
            assertCatalogMatches(scenarios, results);
            String report = AcceptanceReport.render(NeoForgeP1Simulation.metadata(PLATFORM), results);
            finishOnce(server, report);
        } catch (Throwable throwable) {
            LOGGER.error("P1 验收驱动失败：{}", throwable.getMessage());
            List<ScenarioResult> fallback =
                    Collections.singletonList(
                            new ScenarioResult(
                                    "framework",
                                    "driver-error",
                                    ScenarioStatus.ERROR,
                                    0L,
                                    String.valueOf(throwable.getMessage())));
            finishOnce(server, AcceptanceReport.render(fallback));
        }
    }

    private void runMatrixAndReport(MinecraftServer server, ServerGameTestRegistry registry) {
        try {
            List<ScenarioResult> results =
                    ServerGameTestRunner.runAll(
                            registry.all(), test -> new NeoForgeServerGameTestContext(server));
            finishOnce(
                    server,
                    MatrixAcceptanceReportV2.renderReport(channel.client(), results));
        } catch (Throwable throwable) {
            finishOnce(
                    server,
                    MatrixAcceptanceReportV2.renderFailure(
                            acceptanceClient(),
                            "执行 NeoForge 矩阵验收驱动失败："
                                    + MatrixAcceptanceReportV2.describe(throwable)));
        }
    }

    private static void assertCatalogMatches(List<String> required, List<ScenarioResult> results) {
        List<String> actual = new ArrayList<>();
        for (ScenarioResult result : results) {
            actual.add(result.getSuite() + "/" + result.getId());
        }
        if (!required.equals(actual)) {
            throw new IllegalStateException(
                    "NeoForge realserver 场景与 P1 矩阵不一致：actual=" + actual + " matrix=" + required);
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
        finishOnce(null, AcceptanceReport.render(fallback));
    }

    private void watchdogMatrix(long deadlineMs) {
        try {
            Thread.sleep(deadlineMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        finishOnce(
                null,
                MatrixAcceptanceReportV2.renderFailure(
                        acceptanceClient(), "验收绝对截止超时 " + deadlineMs + "ms"));
    }

    private AcceptanceClient acceptanceClient() {
        return channel == null ? null : channel.client();
    }

    private void finishOnce(MinecraftServer server, String report) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        writeReport(report);
        LOGGER.info("realserver NeoForge 验收收尾，权威报告：\n{}", report);
        if (server != null) {
            server.halt(false);
        }
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
                        "mpmt-neoforge-acceptance-halt");
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
                        matrixMode ? MatrixAcceptanceReportV2.REPORT_PROPERTY : REPORT_PROPERTY);
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
            LOGGER.warn("写验收报告失败：{}", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private static final Class<?> SCENARIO_HINT = NeoForgeRealRoundTripServerScenario.class;
}
