package top.wcpe.mc.mpmt.platform.neoforge;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.platform.neoforge.command.NeoForgeMachineCodeCommands;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeConnectionHandle;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeServerTransport;
import top.wcpe.mc.mpmt.platform.neoforge.proxy.ClientProxy;
import top.wcpe.mc.mpmt.platform.neoforge.proxy.ServerProxy;
import top.wcpe.mc.mpmt.platform.neoforge.proxy.SidedProxy;
import top.wcpe.mc.mpmt.platform.neoforge.version.NeoForgeNetworkBindings;
import top.wcpe.mc.mpmt.platform.neoforge.version.NeoForgeServerNetwork;
import top.wcpe.mc.mpmt.platform.neoforge.version.NeoForgeVersions;
import top.wcpe.mc.mpmt.platform.neoforge.version.SupportedVersion;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/** NeoForge mod 入口：探测当前锚点、装配服务端闭环与客户端代理。 */
@Mod("mpmt")
public final class MpmtNeoForgeMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    private static volatile NeoForgeServerTransport activeTransport;

    /** 当前专用服产品闭环，供验收场景驱动真实产品 API（非产品业务入口）。 */
    private static final AtomicReference<NeoForgeServerServices> ACTIVE_SERVICES = new AtomicReference<>();

    private final NeoForgeServerTransport transport;
    private MpmtRuntime runtime;
    private NeoForgeServerServices services;

    public MpmtNeoForgeMod() {
        NeoForgeServerNetwork network = detectServerNetwork();
        transport = new NeoForgeServerTransport(network);
        activeTransport = transport;
        NeoForge.EVENT_BUS.register(this);

        SidedProxy proxy =
                FMLEnvironment.dist == Dist.CLIENT
                        ? new ClientProxy(transport)
                        : new ServerProxy();
        proxy.init();
        LOGGER.info("MPMT NeoForge 已选择网络适配锚点 {}", SupportedVersion.V1_20_2.mcVersion());
    }

    /** 运行期探测 Minecraft 版本并构造对应 L4 服务端网络适配器。 */
    public static NeoForgeServerNetwork detectServerNetwork() {
        return detectServerNetwork(NeoForgeVersions::probeMcVersion);
    }

    /** 使用注入探测源选择 L4 adapter（纯 JVM 测试入口）。 */
    public static NeoForgeServerNetwork detectServerNetwork(Supplier<String> versionProbe) {
        Objects.requireNonNull(versionProbe, "versionProbe 不能为空");
        SupportedVersion version = NeoForgeVersions.detect(versionProbe);
        LOGGER.info("MPMT NeoForge 探测到 Minecraft 版本 {}", version.mcVersion());
        return NeoForgeNetworkBindings.serverNetwork(version);
    }

    /** 服务端已启动：在接收玩家前完成端口、封禁服务与网络特性的装配。 */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        MpmtRuntime assembledRuntime = new MpmtRuntime();
        PlatformAssemblyContext context =
                new PlatformAssemblyContext()
                        .register(MinecraftServer.class, server)
                        .register(NeoForgeServerTransport.class, transport);
        PlatformProvider.boot(getClass().getClassLoader(), assembledRuntime, context);
        NeoForgeServerServices assembledServices = NeoForgeServerServices.install(assembledRuntime);
        assembledRuntime.enable();
        runtime = assembledRuntime;
        services = assembledServices;
        ACTIVE_SERVICES.set(assembledServices);
        LOGGER.info("MPMT 已装配并启用，活跃平台：{}", PlatformProvider.get().platformId());
    }

    /** 注册 NeoForge 原生 Brigadier 运维命令。 */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        NeoForgeMachineCodeCommands.register(event.getDispatcher(), this::currentBanService);
    }

    /** 玩家进入 PLAY 阶段时登记唯一物理连接。 */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        NeoForgeServerServices current = services;
        if (current == null || !(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        NeoForgeConnectionHandle connection =
                transport.onConnected((ServerPlayer) event.getEntity());
        current.networkFeature().onConnected(connection);
    }

    /** 玩家退出时清理该物理连接的握手、会话与可靠性状态。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        NeoForgeServerServices current = services;
        if (current == null || !(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        NeoForgeConnectionHandle connection =
                transport.onDisconnected((ServerPlayer) event.getEntity());
        if (connection != null) {
            current.networkFeature().onDisconnected(connection);
        }
    }

    /** 服务端停止：停用运行时并释放连接与进程级平台绑定。 */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        MpmtRuntime current = runtime;
        if (current != null) {
            closeSchedulerPort(current);
            if (current.phase() == MpmtRuntime.Phase.ENABLED) {
                current.disable();
            }
        }
        transport.clearConnections();
        runtime = null;
        services = null;
        ACTIVE_SERVICES.set(null);
        PlatformProvider.deactivate();
    }

    private static void closeSchedulerPort(MpmtRuntime currentRuntime) {
        try {
            SchedulerPort scheduler = currentRuntime.ports().get(SchedulerPort.class);
            if (scheduler instanceof AutoCloseable) {
                ((AutoCloseable) scheduler).close();
            }
        } catch (Exception error) {
            LOGGER.warn("关闭 NeoForge 调度端口失败：{}", error.toString());
        }
    }

    /** 验收驱动经活跃产品传输发送跨端字节，不暴露可变传输实例。 */
    public static void sendActive(ServerPlayer player, byte[] data) {
        NeoForgeServerTransport current = activeTransport;
        if (current == null) {
            throw new IllegalStateException("NeoForge 产品传输尚未初始化");
        }
        current.send(current.onConnected(player), data);
    }

    /** 已启用的真实产品服务端网络特性，供验收场景驱动产品 API。 */
    public static ServerNetworkFeature serverNetworkFeature() {
        NeoForgeServerServices current = ACTIVE_SERVICES.get();
        if (current == null) {
            throw new IllegalStateException("NeoForge 服务端产品网络尚未启用");
        }
        return current.networkFeature();
    }

    /** 已启用的真实产品服务端传输，供验收取得与产品栈一致的连接句柄。 */
    public static NeoForgeServerTransport serverTransport() {
        NeoForgeServerTransport current = activeTransport;
        if (current == null) {
            throw new IllegalStateException("NeoForge 服务端传输尚未启用");
        }
        return current;
    }

    private BanService currentBanService() {
        NeoForgeServerServices current = services;
        return current == null ? null : current.banService();
    }
}
