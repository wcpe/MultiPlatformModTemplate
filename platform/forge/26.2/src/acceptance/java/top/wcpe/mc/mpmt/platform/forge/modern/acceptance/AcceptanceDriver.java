package top.wcpe.mc.mpmt.platform.forge.modern.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRegistry;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRunner;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReportV2;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReportV2Factory;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReportV2Renderer;
import top.wcpe.mc.mpmt.acceptance.report.JavaRuntimeInfo;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.platform.forge.modern.acceptance.scenario.ClientHudScenario;
import top.wcpe.mc.mpmt.platform.forge.modern.acceptance.scenario.ProductHandshakeScenario;
import top.wcpe.mc.mpmt.platform.forge.modern.acceptance.scenario.ProductRoundtripScenario;

/** 同栈驱动 Forge 真实服务端场景并写出单一 v2 权威报告。 */
public final class AcceptanceDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");
    private static final String REPORT_PROPERTY = "mpmt.acceptance.report";
    private static final String DEADLINE_PROPERTY = "mpmt.acceptance.deadlineMs";
    private static final String RUN_ID_PROPERTY = "mpmt.acceptance.runId";
    private static final String MATRIX_PROPERTY = "mpmt.acceptance.matrix";
    private static final String START_EPOCH_PROPERTY = "mpmt.acceptance.startEpochMs";
    private static final String JAVA_EXECUTABLE_PROPERTY = "mpmt.acceptance.javaExecutable";
    private static final String ARTIFACT_PROPERTY_PREFIX = "mpmt.acceptance.artifact.";
    /** 与真服看门狗 DEADLINE_MS=600s 对齐；可被 -Dmpmt.acceptance.deadlineMs 覆盖。 */
    private static final long DEFAULT_DEADLINE_MS = 600_000L;
    private static final long HALT_GRACE_MS = 8_000L;

    private AcceptanceDriver() {
        // 驱动类不实例化
    }

    public static void start(MinecraftServer server, AcceptanceClient client) {
        ServerGameTestRegistry registry = registry(client);
        AtomicBoolean finished = new AtomicBoolean(false);
        Thread driver = new Thread(
                () -> runAndReport(server, registry, client, finished),
                "mpmt-验收驱动");
        driver.setDaemon(true);
        driver.start();
        Thread watchdog = new Thread(
                () -> watchdog(server, client, finished, deadlineMs()),
                "mpmt-验收看门狗");
        watchdog.setDaemon(true);
        watchdog.start();
        LOGGER.info("真实服务端验收驱动已启动：平台=Forge");
    }

    private static ServerGameTestRegistry registry(AcceptanceClient client) {
        ServerGameTestRegistry registry = new ServerGameTestRegistry();
        register(registry, client, new ProductHandshakeScenario());
        register(registry, client, new ProductRoundtripScenario());
        register(registry, client, new ClientHudScenario());
        return registry;
    }

    private static void register(
            ServerGameTestRegistry registry, AcceptanceClient client, ServerScenario scenario) {
        scenario.bindClient(client);
        registry.register(scenario);
    }

    private static void runAndReport(
            MinecraftServer server,
            ServerGameTestRegistry registry,
            AcceptanceClient client,
            AtomicBoolean finished) {
        List<ScenarioResult> results = ServerGameTestRunner.runAll(
                registry.all(), ignored -> new ForgeServerGameTestContext(server));
        finishOnce(server, finished, renderReport(client, results));
    }

    private static void watchdog(
            MinecraftServer server,
            AcceptanceClient client,
            AtomicBoolean finished,
            long deadlineMs) {
        try {
            Thread.sleep(deadlineMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        finishOnce(
                server,
                finished,
                renderFailure(client, null, "验收绝对截止超时 " + deadlineMs + "ms"));
    }

    private static String renderReport(AcceptanceClient client, List<ScenarioResult> results) {
        JavaRuntimeInfo serverJava = null;
        try {
            serverJava = AcceptanceReportV2Factory.currentJava(requiredProperty(JAVA_EXECUTABLE_PROPERTY));
            AcceptanceReportV2 report = AcceptanceReportV2Factory.create(
                    requiredProperty(RUN_ID_PROPERTY),
                    requiredProperty(MATRIX_PROPERTY),
                    Long.parseLong(requiredProperty(START_EPOCH_PROPERTY)),
                    serverJava,
                    requiredClientJava(client),
                    artifactPath("server-runtime"),
                    artifactPath("server-product"),
                    artifactPath("server-acceptance"),
                    artifactPath("client-product"),
                    artifactPath("client-acceptance"),
                    results);
            return AcceptanceReportV2Renderer.render(report);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("装配 v2 验收报告失败：{}", e.getMessage());
            return renderFailure(client, serverJava, e.getMessage());
        }
    }

    private static String renderFailure(
            AcceptanceClient client, JavaRuntimeInfo serverJava, String message) {
        AcceptanceReportV2 failure = AcceptanceReportV2Factory.failure(
                System.getProperty(RUN_ID_PROPERTY),
                System.getProperty(MATRIX_PROPERTY),
                startEpochOrInvalid(),
                serverJavaOrNull(serverJava),
                client.clientJava(),
                message);
        return AcceptanceReportV2Renderer.render(failure);
    }

    private static JavaRuntimeInfo serverJavaOrNull(JavaRuntimeInfo serverJava) {
        if (serverJava != null) {
            return serverJava;
        }
        try {
            return AcceptanceReportV2Factory.currentJava(
                    System.getProperty(JAVA_EXECUTABLE_PROPERTY));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JavaRuntimeInfo requiredClientJava(AcceptanceClient client) {
        JavaRuntimeInfo clientJava = client.clientJava();
        if (clientJava == null) {
            throw new IllegalStateException("客户端未上报 Java 运行身份");
        }
        return clientJava;
    }

    private static Path artifactPath(String role) {
        return Paths.get(requiredProperty(ARTIFACT_PROPERTY_PREFIX + role));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("缺少系统属性 -D" + name);
        }
        return value;
    }

    private static long startEpochOrInvalid() {
        try {
            return Long.parseLong(System.getProperty(START_EPOCH_PROPERTY, "-1"));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static long deadlineMs() {
        String raw = System.getProperty(DEADLINE_PROPERTY);
        if (raw == null) {
            return DEFAULT_DEADLINE_MS;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            LOGGER.warn("非法验收截止 {}，回退 {}ms", raw, DEFAULT_DEADLINE_MS);
            return DEFAULT_DEADLINE_MS;
        }
    }

    private static void finishOnce(
            MinecraftServer server, AtomicBoolean finished, String report) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        writeReport(report);
        LOGGER.info("真实服务端验收完成：平台=Forge，权威报告：\n{}", report);
        server.halt(false);
        startHardHalt();
    }

    private static void writeReport(String report) {
        String path = System.getProperty(REPORT_PROPERTY);
        if (path == null) {
            LOGGER.warn("未指定验收报告路径，跳过写文件");
            return;
        }
        try {
            Path file = Paths.get(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, report.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.error("写验收报告失败：{}", path, e);
        }
    }

    private static void startHardHalt() {
        Thread hardHalt = new Thread(
                () -> {
                    try {
                        Thread.sleep(HALT_GRACE_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    Runtime.getRuntime().halt(0);
                },
                "mpmt-验收强制退出");
        hardHalt.setDaemon(true);
        hardHalt.start();
    }
}
