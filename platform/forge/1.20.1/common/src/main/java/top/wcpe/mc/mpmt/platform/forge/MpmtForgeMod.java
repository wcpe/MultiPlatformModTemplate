package top.wcpe.mc.mpmt.platform.forge;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.platform.forge.command.ForgeMachineCodeCommands;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeConnectionHandle;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeServerTransport;
import top.wcpe.mc.mpmt.platform.forge.proxy.ClientProxy;
import top.wcpe.mc.mpmt.platform.forge.proxy.ServerProxy;
import top.wcpe.mc.mpmt.platform.forge.proxy.SidedProxy;
import top.wcpe.mc.mpmt.platform.forge.version.ForgeNetworkBindings;
import top.wcpe.mc.mpmt.platform.forge.version.ForgeServerNetwork;
import top.wcpe.mc.mpmt.platform.forge.version.ForgeVersions;
import top.wcpe.mc.mpmt.platform.forge.version.SupportedVersion;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/** Forge mod 入口：探测当前锚点、装配服务端闭环与客户端代理。 */
@Mod("mpmt")
public final class MpmtForgeMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    private static volatile ForgeServerTransport activeTransport;

    /** 当前专用服产品闭环，供验收场景驱动真实产品 API（非产品业务入口）。 */
    private static final AtomicReference<ForgeServerServices> ACTIVE_SERVICES = new AtomicReference<>();

    private final ForgeServerTransport transport;
    private MpmtRuntime runtime;
    private ForgeServerServices services;

    public MpmtForgeMod() {
        ForgeServerNetwork network = detectServerNetwork();
        transport = new ForgeServerTransport(network);
        activeTransport = transport;
        MinecraftForge.EVENT_BUS.register(this);

        SidedProxy proxy =
                FMLEnvironment.dist == Dist.CLIENT
                        ? new ClientProxy(transport)
                        : new ServerProxy();
        proxy.init();
        LOGGER.info("MPMT Forge 已选择网络适配锚点 {}", network.channelId());
    }

    /** 运行期探测 Minecraft 版本并构造对应 L4 服务端网络适配器。 */
    public static ForgeServerNetwork detectServerNetwork() {
        return detectServerNetwork(ForgeVersions::probeMcVersion);
    }

    /** 使用注入探测源选择 L4 adapter（纯 JVM 测试入口）。 */
    public static ForgeServerNetwork detectServerNetwork(Supplier<String> versionProbe) {
        Objects.requireNonNull(versionProbe, "versionProbe 不能为空");
        SupportedVersion version = ForgeVersions.detect(versionProbe);
        LOGGER.info("MPMT Forge 探测到 Minecraft 版本 {}", version.mcVersion());
        return ForgeNetworkBindings.serverNetwork(version);
    }

    /** 服务端已启动：在接收玩家前完成端口、封禁服务与网络特性的装配。 */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        MpmtRuntime assembledRuntime = new MpmtRuntime();
        PlatformAssemblyContext context =
                new PlatformAssemblyContext()
                        .register(MinecraftServer.class, server)
                        .register(ForgeServerTransport.class, transport);
        PlatformProvider.boot(getClass().getClassLoader(), assembledRuntime, context);
        ForgeServerServices assembledServices = ForgeServerServices.install(assembledRuntime);
        assembledRuntime.enable();
        runtime = assembledRuntime;
        services = assembledServices;
        ACTIVE_SERVICES.set(assembledServices);
        LOGGER.info("MPMT 已装配并启用，活跃平台：{}", PlatformProvider.get().platformId());
    }

    /** 注册 Forge 原生 Brigadier 运维命令。 */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ForgeMachineCodeCommands.register(event.getDispatcher(), this::currentBanService);
    }

    /** 玩家进入 PLAY 阶段时登记唯一物理连接。 */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        ForgeServerServices current = services;
        if (current == null || !(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        ForgeConnectionHandle connection = transport.onConnected((ServerPlayer) event.getEntity());
        current.networkFeature().onConnected(connection);
    }

    /** 玩家退出时清理该物理连接的握手、会话与可靠性状态。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ForgeServerServices current = services;
        if (current == null || !(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        ForgeConnectionHandle connection = transport.onDisconnected((ServerPlayer) event.getEntity());
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
            LOGGER.warn("关闭 Forge 调度端口失败：{}", error.toString());
        }
    }

    /** 验收驱动经活跃产品传输发送跨端字节，不暴露可变传输实例。 */
    public static void sendActive(ServerPlayer player, byte[] data) {
        ForgeServerTransport current = activeTransport;
        if (current == null) {
            throw new IllegalStateException("Forge 产品传输尚未初始化");
        }
        current.send(current.onConnected(player), data);
    }

    /** 已启用的真实产品服务端网络特性，供验收场景驱动产品 API。 */
    public static ServerNetworkFeature serverNetworkFeature() {
        ForgeServerServices current = ACTIVE_SERVICES.get();
        if (current == null) {
            throw new IllegalStateException("Forge 服务端产品网络尚未启用");
        }
        return current.networkFeature();
    }

    /** 已启用的真实产品服务端传输，供验收取得与产品栈一致的连接句柄。 */
    public static ForgeServerTransport serverTransport() {
        ForgeServerTransport current = activeTransport;
        if (current == null) {
            throw new IllegalStateException("Forge 服务端传输尚未启用");
        }
        return current;
    }

    private BanService currentBanService() {
        ForgeServerServices current = services;
        return current == null ? null : current.banService();
    }
}
