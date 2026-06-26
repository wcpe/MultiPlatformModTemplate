package top.wcpe.mc.mpmt.platform.forge.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
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
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRegistry;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestRunner;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;
import top.wcpe.mc.mpmt.platform.forge.acceptance.client.ForgeAcceptanceClientInit;
import top.wcpe.mc.mpmt.platform.forge.acceptance.scenario.ForgeSmokeServerScenario;

/**
 * Forge realserver 验收驱动 mod（仅验收运行期用，非产品 mod，ADR-0014）：仅当 {@code -Dmpmt.acceptance=true}
 * 激活；激活后注册控制通道、经 {@code ServiceLoader} 发现场景，在服务端启动后起驱动线程跑
 * {@link ServerGameTestRunner}、写单一权威报告、收尾停服。看门狗绝对截止兜底 + CAS 单次收尾 + 硬退。
 *
 * <p>Forge dev run 因 FG6/FML 模块层不向 mod 暴露库依赖而不可用，故 Forge 与 Bukkit 同走 realserver：
 * 真实 Forge 专用服 + 独立 shaded acceptance mod jar。「客户端」用<b>真正的 Forge 客户端伴侣</b>
 * （{@code ForgeAcceptanceClientCompanion}，仅 {@code Dist.CLIENT} 激活）连入真实 Forge 服——Forge↔Forge
 * 走 FML 握手、mod 通道可用（vanilla/Fabric 客户端不做 FML 握手，跨栈通道不通，故不复用 Fabric 伴侣）。
 */
@Mod("mpmt_acceptance")
public final class MpmtForgeAcceptanceMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");

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

    private final boolean activated;

    /** 控制通道：**构造期创建并注册**（Forge 注册阶段过后会锁定，ServerStarted 再注册会失败）；未激活则为 null。 */
    private final ForgeAcceptanceControlChannel channel;

    public MpmtForgeAcceptanceMod() {
        this.activated = "true".equals(System.getProperty(ACTIVATION_PROPERTY));
        if (!activated) {
            this.channel = null;
            LOGGER.info("验收驱动未激活（-Dmpmt.acceptance=true 开启），mod 空载");
            return;
        }
        // 控制通道须在 mod 构造期（注册阶段）注册 EventNetworkChannel，与产品 ForgeServerTransport 一致；
        // 服务端在 ServerStarted 再绑定。玩家断开监听用 Forge 事件总线。
        this.channel = new ForgeAcceptanceControlChannel();
        MinecraftForge.EVENT_BUS.register(this);
        // 客户端侧：在同一验收通道上注册客户端伴侣（仅 Dist.CLIENT，客户端专有类不被服务端加载，ADR-0014）。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeAcceptanceClientInit.activate(channel.channel());
        }
        LOGGER.info("realserver Forge 验收驱动已激活，待服务端启动");
    }

    /** 服务端启动：绑定服务端、装配场景，起驱动线程与看门狗线程。 */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!activated) {
            return;
        }
        MinecraftServer server = event.getServer();
        channel.bindServer(server);

        // 装配场景：ServiceLoader 发现 + 绑定排程客户端
        ServerGameTestRegistry registry = new ServerGameTestRegistry();
        for (ServerScenario scenario :
                ServiceLoader.load(ServerScenario.class, getClass().getClassLoader())) {
            scenario.bindClient(channel.client());
            registry.register(scenario);
        }

        long deadline = deadlineMs();
        Thread driver =
                new Thread(() -> runAndReport(server, registry), "mpmt-forge-acceptance-driver");
        driver.setDaemon(true);
        driver.start();
        Thread watchdog =
                new Thread(() -> watchdog(deadline), "mpmt-forge-acceptance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        LOGGER.info("realserver Forge 验收驱动已就绪，等待客户端连入");
    }

    /** 客户端断开：唤醒所有挂起步骤，避免驱动线程久等。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ForgeAcceptanceControlChannel current = channel;
        if (current != null) {
            current.onClientDisconnected();
        }
    }

    /** 驱动线程：顺序跑全部场景 → 渲染权威报告 → 收尾。 */
    private void runAndReport(MinecraftServer server, ServerGameTestRegistry registry) {
        List<ScenarioResult> results =
                ServerGameTestRunner.runAll(
                        registry.all(), test -> new ForgeServerGameTestContext(server));
        finishOnce(server, AcceptanceReport.render(results));
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
        finishOnce(null, AcceptanceReport.render(fallback));
    }

    /** 收尾单次（CAS）：写报告 → 停服 → 硬退兜底。 */
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

    /** 显式引用，确保 Forge 场景类被打入并可被 ServiceLoader 发现（编译期可见性占位）。 */
    @SuppressWarnings("unused")
    private static final Class<?> SCENARIO_HINT = ForgeSmokeServerScenario.class;
}
