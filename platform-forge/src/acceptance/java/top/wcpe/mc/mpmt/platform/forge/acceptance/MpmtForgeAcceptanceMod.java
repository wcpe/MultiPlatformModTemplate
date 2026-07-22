package top.wcpe.mc.mpmt.platform.forge.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTest;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRunner;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;
import top.wcpe.mc.mpmt.platform.forge.acceptance.client.ForgeAcceptanceClientInit;
import top.wcpe.mc.mpmt.platform.forge.acceptance.scenario.ForgeRealRoundTripServerScenario;
import top.wcpe.mc.mpmt.platform.forge.acceptance.sim.ForgeP1Simulation;

/**
 * Forge realserver 验收驱动 mod（仅验收运行期用，非产品 mod，ADR-0014）：仅当 {@code -Dmpmt.acceptance=true}
 * 激活。跑完整 P1 REAL_REQUIRED（13 项进程内回环 + {@code real-round-trip} 真客户端往返），输出 acceptance v2
 * 权威报告。
 *
 * <p>「客户端」用真正的 Forge 客户端伴侣连入（FML 握手；vanilla/Fabric 伴侣过不了 Forge 握手）。
 */
@Mod("mpmt_acceptance")
public final class MpmtForgeAcceptanceMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");

    private static final String ACTIVATION_PROPERTY = "mpmt.acceptance";
    private static final String REPORT_PROPERTY = "mpmt.acceptance.report";
    private static final String DEADLINE_PROPERTY = "mpmt.acceptance.deadlineMs";
    private static final String PLATFORM = "forge";
    private static final long DEFAULT_DEADLINE_MS = 660_000L;
    private static final long HALT_GRACE_MS = 8_000L;

    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final boolean activated;
    private final ForgeAcceptanceControlChannel channel;

    public MpmtForgeAcceptanceMod() {
        this.activated = "true".equals(System.getProperty(ACTIVATION_PROPERTY));
        if (!activated) {
            this.channel = null;
            LOGGER.info("验收驱动未激活（-Dmpmt.acceptance=true 开启），mod 空载");
            return;
        }
        this.channel = new ForgeAcceptanceControlChannel();
        MinecraftForge.EVENT_BUS.register(this);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeAcceptanceClientInit.activate(channel);
        }
        LOGGER.info("realserver Forge 验收驱动已激活，待服务端启动（P1 REAL_REQUIRED + v2 报告）");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!activated) {
            return;
        }
        MinecraftServer server = event.getServer();
        channel.bindServer(server);

        // 第 14 项：真往返；前 13 项在驱动线程内 runLoopbackCore（不经 ServiceLoader）
        ServerScenario roundTrip = new ForgeRealRoundTripServerScenario();
        roundTrip.bindClient(channel.client());

        long deadline = deadlineMs();
        Thread driver =
                new Thread(() -> runAndReport(server, roundTrip), "mpmt-forge-acceptance-driver");
        driver.setDaemon(true);
        driver.start();
        Thread watchdog = new Thread(() -> watchdog(deadline), "mpmt-forge-acceptance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        LOGGER.info("realserver Forge 验收驱动已就绪（13 回环 + real-round-trip 等客户端）");
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ForgeAcceptanceControlChannel current = channel;
        if (current != null) {
            current.onClientDisconnected();
        }
    }

    private void runAndReport(MinecraftServer server, ServerScenario roundTrip) {
        // 先跑 13 项进程内回环（不依赖客户端），再跑真往返
        List<ScenarioResult> results = new ArrayList<>(ForgeP1Simulation.runLoopbackCore());
        List<ServerGameTest> live = Collections.singletonList(roundTrip);
        results.addAll(
                ServerGameTestRunner.runAll(live, test -> new ForgeServerGameTestContext(server)));
        List<String> scenarios = P1ScenarioMatrix.requiredFor(PLATFORM);
        assertCatalogMatches(scenarios, results);
        // 复用 Simulation 元数据（含 productJar → SHA 回退），避免 FG run 配置期无法注入动态 SHA
        String report = AcceptanceReport.render(ForgeP1Simulation.metadata(PLATFORM), results);
        finishOnce(server, report);
    }

    private static void assertCatalogMatches(List<String> required, List<ScenarioResult> results) {
        List<String> actual = new ArrayList<>();
        for (ScenarioResult result : results) {
            actual.add(result.getSuite() + "/" + result.getId());
        }
        if (!required.equals(actual)) {
            throw new IllegalStateException(
                    "Forge realserver 场景与 P1 矩阵不一致：actual=" + actual + " matrix=" + required);
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
        finishOnce(null, AcceptanceReport.render(fallback));
    }

    private void finishOnce(MinecraftServer server, String report) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        writeReport(report);
        LOGGER.info("realserver Forge 验收收尾，权威报告：\n{}", report);
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
                        "mpmt-forge-acceptance-halt");
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
            LOGGER.warn("写验收报告失败：{}", e.getMessage());
        }
    }

    /** 编译期占位：确保真往返场景类打入验收 jar。 */
    @SuppressWarnings("unused")
    private static final Class<?> SCENARIO_HINT = ForgeRealRoundTripServerScenario.class;
}
