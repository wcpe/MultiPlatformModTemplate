package top.wcpe.mc.mpmt.platform.fabric.gametest.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTest;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRunner;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReportMetadata;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.platform.fabric.gametest.FabricServerGameTestContext;

/**
 * 模拟服 GameTest 驱动引导（FR-23①，仅 gametest 接入层）：经系统属性 {@code -Dmpmt.simtest=true} 激活，
 * 在真实 Fabric 服务端启动后跑回环网络 GameTest（无外部客户端、in-process 回环）、聚合权威报告写文件、关停。
 *
 * <p>与 realserver 驱动（{@code AcceptanceDriverBootstrap}）互补：本套件 headless 自动跑、可作 CI 快速门；
 * realserver 需真实客户端进程。报告路径 {@code -Dmpmt.simtest.report=<path>}，Gradle 读末行 {@code RESULT PASS}。
 */
public final class SimDriverBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-simtest");

    private static final String ACTIVATION_PROPERTY = "mpmt.simtest";
    private static final String REPORT_PROPERTY = "mpmt.simtest.report";
    private static final String COMMIT_PROPERTY = "mpmt.simtest.commit";
    private static final String VERSION_PROPERTY = "mpmt.simtest.version";
    private static final String MC_VERSION_PROPERTY = "mpmt.simtest.mcVersion";
    private static final String SERVER_VERSION_PROPERTY = "mpmt.simtest.serverVersion";
    private static final String PRODUCT_SHA_PROPERTY = "mpmt.simtest.productJarSha256";
    private static final String PLATFORM = "sim-fabric";
    private static final long HALT_GRACE_MS = 8_000L;

    private SimDriverBootstrap() {
        // 工具类不实例化
    }

    /** 由 gametest 入口调用：仅当激活时挂接服务端启动事件。 */
    public static void register() {
        if (!"true".equals(System.getProperty(ACTIVATION_PROPERTY))) {
            return;
        }
        ServerLifecycleEvents.SERVER_STARTED.register(SimDriverBootstrap::onServerStarted);
        LOGGER.info("模拟服 GameTest 驱动已激活，待服务端启动");
    }

    private static void onServerStarted(MinecraftServer server) {
        // 驱动线程（守护）：跑回环 GameTest（不碰世界态、纯网络回环）→ 报告 → 关停
        Thread driver = new Thread(() -> runAndReport(server), "mpmt-simtest-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private static void runAndReport(MinecraftServer server) {
        List<ServerGameTest> tests = SimScenarioCatalog.all();
        List<String> required = P1ScenarioMatrix.requiredFor(PLATFORM);
        if (!required.equals(SimScenarioCatalog.scenarioIds())) {
            throw new IllegalStateException("Fabric 模拟服场景目录与 P1 矩阵不一致");
        }
        List<ScenarioResult> results =
                ServerGameTestRunner.runAll(tests, test -> new FabricServerGameTestContext(server));
        String report = AcceptanceReport.render(metadata(required), results);
        writeReport(report);
        LOGGER.info("模拟服 GameTest 完成，权威报告：\n{}", report);
        server.halt(false);
        startHardHalt();
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
            throw new IllegalStateException("缺少模拟服报告元数据属性：" + name);
        }
        return value;
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
                        "mpmt-simtest-halt");
        hardHalt.setDaemon(true);
        hardHalt.start();
    }

    private static void writeReport(String report) {
        String path = System.getProperty(REPORT_PROPERTY);
        if (path == null) {
            LOGGER.warn("未指定报告路径（-D{}），跳过写文件", REPORT_PROPERTY);
            return;
        }
        try {
            Path file = Paths.get(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, report.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.error("写模拟服报告失败：{}", path, e);
        }
    }
}
