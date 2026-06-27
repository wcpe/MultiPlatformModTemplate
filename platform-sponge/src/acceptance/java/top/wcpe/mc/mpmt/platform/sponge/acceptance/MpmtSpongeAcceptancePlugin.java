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
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRegistry;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRunner;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;
import top.wcpe.mc.mpmt.platform.sponge.acceptance.scenario.SpongeSmokeServerScenario;

/**
 * Sponge realserver 验收驱动插件（仅验收运行期用，非产品插件，ADR-0014）：仅当 {@code -Dmpmt.acceptance=true}
 * 激活；激活后在 {@code RegisterChannelEvent} 注册控制通道、服务端启动后经 {@code ServiceLoader} 发现场景，
 * 起驱动线程跑 {@link ServerGameTestRunner}、写单一权威报告、收尾停服。看门狗绝对截止兜底 + CAS 单次收尾 + 硬退。
 *
 * <p>Sponge 无 GameTest，故 realserver 是其唯一实机验收形态：服务端驱动 / 客户端验证 / 单一权威报告。
 * 「客户端」复用我方 Fabric 验收伴侣连入真实 SpongeVanilla 服（异构互通，同 Bukkit 模式）。
 */
@Plugin("mpmt-acceptance")
public final class MpmtSpongeAcceptancePlugin {

    /** 激活开关系统属性。 */
    private static final String ACTIVATION_PROPERTY = "mpmt.acceptance";
    /** 报告输出路径系统属性。 */
    private static final String REPORT_PROPERTY = "mpmt.acceptance.report";
    /** 绝对截止毫秒数系统属性。 */
    private static final String DEADLINE_PROPERTY = "mpmt.acceptance.deadlineMs";
    /** 默认绝对截止（5 分钟）。 */
    private static final long DEFAULT_DEADLINE_MS = 300_000L;
    /** halt 宽限期（毫秒）：收尾后若 JVM 未退则强制 halt。 */
    private static final long HALT_GRACE_MS = 8_000L;

    /** 收尾单次保证（驱动线程与看门狗线程竞争）。 */
    private final AtomicBoolean finished = new AtomicBoolean(false);

    private final Logger logger;
    private final PluginContainer container;

    private SpongeAcceptanceControlChannel channel;

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
        channel = new SpongeAcceptanceControlChannel(logger);
        channel.register(control);
    }

    @Listener
    public void onServerStarted(final StartedEngineEvent<Server> event) {
        if (!activated()) {
            logger.info("验收驱动未激活（-Dmpmt.acceptance=true 开启），插件空载");
            return;
        }
        // 装配场景：ServiceLoader 发现 + 绑定排程客户端
        ServerGameTestRegistry registry = new ServerGameTestRegistry();
        for (ServerScenario scenario :
                ServiceLoader.load(ServerScenario.class, getClass().getClassLoader())) {
            scenario.bindClient(channel.client());
            registry.register(scenario);
        }

        long deadline = deadlineMs();
        Thread driver = new Thread(() -> runAndReport(registry), "mpmt-sponge-acceptance-driver");
        driver.setDaemon(true);
        driver.start();
        Thread watchdog = new Thread(() -> watchdog(deadline), "mpmt-sponge-acceptance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        logger.info("realserver Sponge 验收驱动已激活，等待客户端连入");
    }

    /** 客户端断开：唤醒所有挂起步骤，避免驱动线程久等。 */
    @Listener
    public void onDisconnect(final ServerSideConnectionEvent.Disconnect event) {
        if (channel != null) {
            channel.onClientDisconnected();
        }
    }

    /** 驱动线程：顺序跑全部场景 → 渲染权威报告 → 收尾。 */
    private void runAndReport(ServerGameTestRegistry registry) {
        List<ScenarioResult> results =
                ServerGameTestRunner.runAll(
                        registry.all(), test -> new SpongeServerGameTestContext(container));
        finishOnce(AcceptanceReport.render(results));
    }

    /** 看门狗线程：绝对截止到点仍未收尾 → 写 fallback FAIL 报告并强制收尾。 */
    private void watchdog(long deadlineMs) {
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
        finishOnce(AcceptanceReport.render(fallback));
    }

    /** 收尾单次（CAS）：写报告 → 停服 → 硬退兜底。 */
    private void finishOnce(String report) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        writeReport(report);
        logger.info("realserver Sponge 验收收尾，权威报告：\n{}", report);
        Sponge.server().shutdown();
        startHardHalt();
    }

    /** 硬退兜底：宽限期内 JVM 未退则强制 halt（不跑 shutdown hook）。 */
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
        String path = System.getProperty(REPORT_PROPERTY);
        if (path == null) {
            return;
        }
        try {
            Path file = Paths.get(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, report.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("写验收报告失败：{}", e.getMessage());
        }
    }

    /** 显式引用，确保 Sponge 场景类被打入并可被 ServiceLoader 发现（编译期可见性占位）。 */
    @SuppressWarnings("unused")
    private static final Class<?> SCENARIO_HINT = SpongeSmokeServerScenario.class;
}
