package top.wcpe.mc.mpmt.platform.bukkit.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRegistry;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRunner;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario.BukkitSmokeServerScenario;

/**
 * Bukkit realserver 验收驱动插件（仅验收运行期用，非产品插件，ADR-0014）：仅当 {@code -Dmpmt.acceptance=true}
 * 激活；激活后注册控制通道 + 产品通道出站 + 经 {@code ServiceLoader} 发现场景，起驱动线程跑
 * {@link ServerGameTestRunner}、写单一权威报告、收尾停服。看门狗绝对截止兜底 + CAS 单次收尾 + 硬退。
 *
 * <p>Bukkit 无 GameTest，故 realserver 是其唯一实机验收形态：服务端驱动 / 客户端验证 / 单一权威报告。
 * 「客户端」复用我方 Fabric 验收伴侣连入真实 Paper 服（异构互通，FR-11②）。
 */
public final class MpmtBukkitAcceptancePlugin extends JavaPlugin implements Listener {

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
    /** 产品跨端通道（发 HUD 用）。 */
    private static final String PRODUCT_CHANNEL = "mpmt:main";

    /** 收尾单次保证（驱动线程与看门狗线程竞争）。 */
    private final AtomicBoolean finished = new AtomicBoolean(false);

    private BukkitAcceptanceControlChannel channel;

    @Override
    public void onEnable() {
        if (!"true".equals(System.getProperty(ACTIVATION_PROPERTY))) {
            getLogger().info("验收驱动未激活（-Dmpmt.acceptance=true 开启），插件空载");
            return;
        }
        // 产品通道出站（场景发 HUD 用）+ 控制通道
        getServer().getMessenger().registerOutgoingPluginChannel(this, PRODUCT_CHANNEL);
        channel = new BukkitAcceptanceControlChannel(this);
        channel.register();
        getServer().getPluginManager().registerEvents(this, this);

        // 装配场景：ServiceLoader 发现 + 绑定排程客户端
        ServerGameTestRegistry registry = new ServerGameTestRegistry();
        for (ServerScenario scenario : ServiceLoader.load(ServerScenario.class, getClassLoader())) {
            scenario.bindClient(channel.client());
            registry.register(scenario);
        }

        long deadline = deadlineMs();
        Thread driver = new Thread(() -> runAndReport(registry), "mpmt-bukkit-acceptance-driver");
        driver.setDaemon(true);
        driver.start();
        Thread watchdog = new Thread(() -> watchdog(deadline), "mpmt-bukkit-acceptance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        getLogger().info("realserver Bukkit 验收驱动已激活，等待客户端连入");
    }

    /** 客户端断开：唤醒所有挂起步骤，避免驱动线程久等。 */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (channel != null) {
            channel.onClientDisconnected();
        }
    }

    /** 驱动线程：顺序跑全部场景 → 渲染权威报告 → 收尾。 */
    private void runAndReport(ServerGameTestRegistry registry) {
        List<ScenarioResult> results =
                ServerGameTestRunner.runAll(
                        registry.all(), test -> new BukkitServerGameTestContext(this));
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
        getLogger().info("realserver Bukkit 验收收尾，权威报告：\n" + report);
        getServer().shutdown();
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
            getLogger().warning("写验收报告失败：" + e.getMessage());
        }
    }

    /** 显式引用，确保 Bukkit 场景类被打入并可被 ServiceLoader 发现（编译期可见性占位）。 */
    @SuppressWarnings("unused")
    private static final Class<?> SCENARIO_HINT = BukkitSmokeServerScenario.class;
}
