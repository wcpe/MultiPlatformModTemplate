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
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRegistry;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRunner;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;

/**
 * Fabric realserver 验收驱动引导（仅 gametest 接入层，ADR-0014）：经系统属性 {@code -Dmpmt.acceptance=true} 激活，
 * 在服务端启动后装配控制通道、经 {@code ServiceLoader} 发现并绑定全部 {@link ServerScenario}、驱动执行、
 * 聚合单一权威报告写文件、关停服务端。由 gametest 入口（{@code fabric.mod.json} main entrypoint）调用 {@link #register()}。
 *
 * <p>未激活则 NOP（不影响普通运行）。报告路径经 {@code -Dmpmt.acceptance.report=<path>} 指定，由 Gradle 任务读取
 * 末行 {@code RESULT PASS|FAIL} 作门禁（{@code runRealServerAcceptance}）。
 *
 * <p><b>绝对截止看门狗</b>（ADR-0014 §4）：守护线程到 {@code -Dmpmt.acceptance.deadlineMs} 截止仍未完成，则写 fallback
 * {@code RESULT FAIL} 报告并强制 {@code halt}，防场景在主线程死锁导致整体卡死无报告。收尾经 {@code finished} CAS 单次，
 * 驱动与看门狗谁先到谁写、另一方 no-op。
 */
public final class AcceptanceDriverBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");

    /** 激活开关系统属性。 */
    private static final String ACTIVATION_PROPERTY = "mpmt.acceptance";
    /** 报告输出路径系统属性。 */
    private static final String REPORT_PROPERTY = "mpmt.acceptance.report";
    /** 绝对截止毫秒数系统属性。 */
    private static final String DEADLINE_PROPERTY = "mpmt.acceptance.deadlineMs";
    /** 默认绝对截止（毫秒）。 */
    private static final long DEFAULT_DEADLINE_MS = 300_000L;
    /** 硬退兜底宽限期（毫秒）：halt 后仍未退则强制 halt JVM。 */
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
        LOGGER.info("realserver 验收驱动已激活，待服务端启动");
    }

    private static void onServerStarted(MinecraftServer server) {
        FabricAcceptanceControlChannel channel = new FabricAcceptanceControlChannel(server);
        channel.register();

        ServerGameTestRegistry registry = new ServerGameTestRegistry();
        for (ServerScenario scenario :
                ServiceLoader.load(ServerScenario.class, AcceptanceDriverBootstrap.class.getClassLoader())) {
            scenario.bindClient(channel.client());
            registry.register(scenario);
        }

        // 收尾单次门闩：驱动正常完成 / 看门狗超时，谁先到谁写报告 + halt
        AtomicBoolean finished = new AtomicBoolean(false);

        Thread driver =
                new Thread(() -> runAndReport(server, registry, finished), "mpmt-acceptance-driver");
        driver.setDaemon(true);
        driver.start();

        long deadlineMs = deadlineMs();
        Thread watchdog =
                new Thread(() -> watchdog(server, finished, deadlineMs), "mpmt-acceptance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void runAndReport(
            MinecraftServer server, ServerGameTestRegistry registry, AtomicBoolean finished) {
        List<ScenarioResult> results =
                ServerGameTestRunner.runAll(
                        registry.all(), test -> new FabricServerGameTestContext(server));
        finishOnce(server, finished, AcceptanceReport.render(results));
    }

    private static void watchdog(MinecraftServer server, AtomicBoolean finished, long deadlineMs) {
        try {
            Thread.sleep(deadlineMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        // 到点仍未收尾：写 fallback RESULT FAIL 报告（复用渲染器保格式一致）并强制收尾
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

    private static void finishOnce(MinecraftServer server, AtomicBoolean finished, String report) {
        if (!finished.compareAndSet(false, true)) {
            return; // 已被另一方收尾
        }
        writeReport(report);
        LOGGER.info("realserver 验收收尾，权威报告：\n{}", report);
        server.halt(false);
        startHardHalt();
    }

    /** 硬退兜底：halt 后宽限期内仍未退出 JVM，则强制 halt，防优雅停机卡死。 */
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
