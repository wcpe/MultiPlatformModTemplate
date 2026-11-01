package top.wcpe.mc.mpmt.platform.forge;

import java.io.File;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkCheckHandler;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import top.wcpe.mc.mpmt.core.client.DefaultMachineCodeProvider;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.kernel.port.SchedulerPort;
import top.wcpe.mc.mpmt.platform.forge.client.ForgeClientSession;
import top.wcpe.mc.mpmt.platform.forge.hud.ForgeHudAdapter;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeClientTransport;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * Forge 1.12.2 产品入口：客户端做握手 / HUD，服务端装配能力端口与功能域。
 *
 * <p>曾经声明为 {@code clientSideOnly}——那样服务端根本不会加载本 mod，也就没有任何装配点，
 * 域与端口在这条车道无法存在。现在两端都加载，但客户端路径逐字不变：
 * {@code preInitialize} 按 side 分流，服务端侧在 {@code FMLServerStartingEvent} 里装配
 * {@code Forge112CapabilityBootstrap}（端口 + 功能域），停服时逆序停用。
 *
 * <p><b>融合服（ADR-0008 / ADR-0021 决策 8）</b>：同进程存在 Bukkit 时（CatServer 等），
 * 我方唯一活跃平台必须是 Bukkit，故 Forge 侧直接不装配运行时——不是靠 {@code clientSideOnly}
 * 让 mod 不加载，而是加载后主动让位，这样仍能在融合服上正确读取本 mod 的版本信息。
 */
@Mod(
        modid = MpmtForgeMod.MOD_ID,
        name = "MultiPlatformModTemplate",
        version = ForgeBuildInfo.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        acceptableRemoteVersions = "*",
        useMetadata = true)
public final class MpmtForgeMod {

    public static final String MOD_ID = "mpmt";
    public static final String PRODUCT_CHANNEL = "MPMT";

    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private static volatile ForgeClientSession activeSession;
    private static volatile boolean connected;
    private static volatile boolean optionalCheckAccepted;
    private static volatile boolean remoteForgeProductAbsent;

    private ForgeClientSession session;
    private volatile boolean joinStarted;

    /** 本侧是否为客户端；服务端为 false，客户端专属入口全部靠它短路。 */
    private static volatile boolean clientSide;

    /** FML 提供的配置目录（端口基目录相对它解析）；在 {@code preInitialize} 里取一次。 */
    private static volatile File configDirectory;

    /** 服务端运行时；仅在服务端启动装配成功后才非空。 */
    private MpmtRuntime runtime;

    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        configDirectory = event.getModConfigurationDirectory();
        clientSide = event.getSide().isClient();
        if (!clientSide) {
            // 服务端：不建客户端会话、不注册客户端监听器（Minecraft.getMinecraft() 在服务端会崩），
            // 能力端口留到 FMLServerStartingEvent 里装配。
            LOGGER.info("MPMT Forge 1.12.2 已在服务端加载，等待服务端启动后装配能力端口");
            return;
        }
        ForgeClientTransport transport = new ForgeClientTransport(PRODUCT_CHANNEL);
        session =
                new ForgeClientSession(
                        transport,
                        new ForgeHudAdapter(),
                        ForgeBuildInfo.VERSION,
                        new DefaultMachineCodeProvider());
        activeSession = session;
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("MPMT Forge 1.12.2 客户端产品已初始化，等待进入服务端后启动握手");
    }

    /** 服务端启动：装配端口与功能域并启用运行时（须在接收玩家前完成）。 */
    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        if (hybridServer()) {
            // ADR-0008 / ADR-0021 决策 8：融合服（CatServer 等）上我方唯一活跃平台必须是 Bukkit。
            // Forge 侧不装配运行时——若这里也 boot，PlatformProvider 会因进程级标记失败快（服务器直接崩），
            // 而“让 Forge 侧静默不激活”才是该不变量的正解。
            LOGGER.info("检测到融合服（同进程存在 Bukkit）：按 ADR-0008 / ADR-0021 只保留 Bukkit 为唯一活跃平台，Forge 侧不装配运行时");
            return;
        }
        File configurationDirectory = configDirectory;
        if (configurationDirectory == null) {
            throw new IllegalStateException("配置目录未就绪：preInitialize 未执行");
        }
        MpmtRuntime assembled = new MpmtRuntime();
        // 与其它车道同构：平台原生对象只经启动上下文流转，装配由 PlatformProvider 驱动，
        // 因此参与进程级“单一活跃平台”把关（ADR-0008 / FR-25）。
        PlatformAssemblyContext context =
                new PlatformAssemblyContext()
                        .register(MinecraftServer.class, event.getServer())
                        .register(File.class, configurationDirectory);
        PlatformProvider.boot(getClass().getClassLoader(), assembled, context);
        assembled.enable();
        runtime = assembled;
        LOGGER.info("MPMT Forge 1.12.2 服务端能力端口与功能域已启用，活跃平台：{}", PlatformProvider.get().platformId());
    }

    /** 停服：逆序停用功能域、释放调度器，并释放进程级平台绑定。 */
    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        MpmtRuntime current = runtime;
        runtime = null;
        if (current == null) {
            return;
        }
        shutdown(current);
        // 清进程级标记：使同 JVM 内重新启用（如融合服 reload）能再次 boot
        PlatformProvider.deactivate();
        LOGGER.info("MPMT Forge 1.12.2 服务端运行时已停用");
    }

    /** 融合服探测：同进程存在 Bukkit（CatServer 等）。 */
    private static boolean hybridServer() {
        try {
            Class.forName("org.bukkit.Bukkit", false, MpmtForgeMod.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void shutdown(MpmtRuntime runtime) {
        if (runtime.phase() == MpmtRuntime.Phase.ENABLED) {
            runtime.disable();
        }
        SchedulerPort scheduler = runtime.ports().get(SchedulerPort.class);
        if (scheduler instanceof AutoCloseable) {
            try {
                ((AutoCloseable) scheduler).close();
            } catch (Exception e) {
                LOGGER.warn("释放 MPMT 调度器失败", e);
            }
        }
    }

    /** 允许连接未安装我方 Forge mod 的服务端。 */
    @NetworkCheckHandler
    public boolean acceptRemoteVersions(Map<String, String> remoteVersions, Side remoteSide) {
        if (remoteSide == Side.SERVER) {
            optionalCheckAccepted = true;
            remoteForgeProductAbsent = !remoteVersions.containsKey(MOD_ID);
        }
        return true;
    }

    /** 网络连接建立后只记录状态，等待玩家世界就绪。 */
    @SubscribeEvent
    public void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        if (!clientSide) {
            return;
        }
        joinStarted = false;
        connected = true;
        LOGGER.info("客户端网络已连接，等待玩家世界就绪后开始 MPMT 产品握手");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!clientSide) {
            return;
        }
        handleClientTick(event.phase, Minecraft.getMinecraft().player != null);
    }

    private void handleClientTick(TickEvent.Phase phase, boolean playerReady) {
        if (phase != TickEvent.Phase.END || !connected || !playerReady || joinStarted) {
            return;
        }
        joinStarted = true;
        session.join();
        LOGGER.info("客户端玩家世界已就绪，开始 MPMT 产品握手");
    }

    @SubscribeEvent
    public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (!clientSide) {
            return;
        }
        connected = false;
        joinStarted = false;
        optionalCheckAccepted = false;
        remoteForgeProductAbsent = false;
        session.disconnect();
        LOGGER.info("客户端已离开服务端，清理 MPMT 产品会话");
    }

    /** 当前产品会话，供独立验收伴侣读取真实产品状态。 */
    public static ForgeClientSession session() {
        ForgeClientSession current = activeSession;
        if (current == null) {
            throw new IllegalStateException("1.12.2 产品会话尚未初始化");
        }
        return current;
    }

    public static boolean isConnected() {
        return connected;
    }

    public static boolean optionalCheckAccepted() {
        return optionalCheckAccepted;
    }

    public static boolean remoteForgeProductAbsent() {
        return remoteForgeProductAbsent;
    }
}
