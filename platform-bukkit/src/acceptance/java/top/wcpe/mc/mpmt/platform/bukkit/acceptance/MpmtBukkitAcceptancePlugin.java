package top.wcpe.mc.mpmt.platform.bukkit.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTest;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRunner;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario.BukkitRealRoundTripServerScenario;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.sim.BukkitP1Simulation;

/**
 * Bukkit realserver 验收驱动插件（仅验收运行期用，非产品插件，ADR-0014）：仅当 {@code -Dmpmt.acceptance=true}
 * 激活。跑完整 P1 REAL_REQUIRED（13 项进程内回环 + {@code real-round-trip} 真客户端往返），输出 acceptance v2
 * 权威报告。
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
    private static final String PRODUCT_CHANNEL = "mpmt:main";

    private final AtomicBoolean finished = new AtomicBoolean(false);

    private BukkitAcceptanceControlChannel channel;

    @Override
    public void onEnable() {
        if (!"true".equals(System.getProperty(ACTIVATION_PROPERTY))) {
            getLogger().info("验收驱动未激活（-Dmpmt.acceptance=true 开启），插件空载");
            return;
        }
        getServer().getMessenger().registerOutgoingPluginChannel(this, PRODUCT_CHANNEL);
        channel = new BukkitAcceptanceControlChannel(this);
        channel.register();
        getServer().getPluginManager().registerEvents(this, this);

        ServerScenario roundTrip = new BukkitRealRoundTripServerScenario();
        roundTrip.bindClient(channel.client());

        long deadline = deadlineMs();
        Thread driver = new Thread(() -> runAndReport(roundTrip), "mpmt-bukkit-acceptance-driver");
        driver.setDaemon(true);
        driver.start();
        Thread watchdog = new Thread(() -> watchdog(deadline), "mpmt-bukkit-acceptance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        getLogger().info("realserver Bukkit 验收驱动已激活（13 回环 + real-round-trip 等客户端）");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (channel != null) {
            channel.onClientDisconnected();
        }
    }

    private void runAndReport(ServerScenario roundTrip) {
        List<ScenarioResult> results = new ArrayList<>(BukkitP1Simulation.runLoopbackCore());
        List<ServerGameTest> live = Collections.singletonList(roundTrip);
        results.addAll(
                ServerGameTestRunner.runAll(live, test -> new BukkitServerGameTestContext(this)));
        String platform = System.getProperty(PLATFORM_PROPERTY, "bukkit");
        List<String> scenarios = P1ScenarioMatrix.requiredFor(platform);
        assertCatalogMatches(scenarios, results);
        String report = AcceptanceReport.render(BukkitP1Simulation.metadata(platform), results);
        finishOnce(report);
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
        // 看门狗 fallback 仍走 v1，避免缺元数据再抛；正常路径必须是 v2
        finishOnce(AcceptanceReport.render(fallback));
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

    /** 编译期占位：确保真往返场景类打入验收 jar。 */
    @SuppressWarnings("unused")
    private static final Class<?> SCENARIO_HINT = BukkitRealRoundTripServerScenario.class;
}
