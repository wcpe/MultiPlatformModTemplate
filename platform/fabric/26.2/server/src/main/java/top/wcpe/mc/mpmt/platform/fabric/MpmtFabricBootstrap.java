package top.wcpe.mc.mpmt.platform.fabric;

import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.platform.fabric.capability.FabricCapabilityBootstrap;
import top.wcpe.mc.mpmt.platform.fabric.command.FabricMachineCodeCommands;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricConnectionHandle;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricServerTransport;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/** Fabric 主入口：装配服务端闭环、原生命令与玩家物理连接生命周期。 */
public final class MpmtFabricBootstrap implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    /** 当前专用服产品闭环，供验收场景驱动真实产品 API（非产品业务入口）。 */
    private static final AtomicReference<FabricServerServices> ACTIVE_SERVICES = new AtomicReference<>();

    /** 当前专用服传输适配，供验收取得与产品栈一致的连接句柄。 */
    private static final AtomicReference<FabricServerTransport> ACTIVE_TRANSPORT = new AtomicReference<>();

    private MpmtRuntime runtime;
    private FabricServerServices services;
    private FabricServerTransport transport;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        FabricMachineCodeCommands.register(dispatcher, this::currentBanService));
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> onPlayerConnected(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> onPlayerDisconnected(handler.player));
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
    }

    private void onServerStarted(MinecraftServer server) {
        MpmtRuntime assembledRuntime = new MpmtRuntime();
        PlatformAssemblyContext context =
                new PlatformAssemblyContext().register(MinecraftServer.class, server);
        PlatformProvider.boot(getClass().getClassLoader(), assembledRuntime, context);
        FabricServerTransport assembledTransport =
                (FabricServerTransport) assembledRuntime.ports()
                        .get(top.wcpe.mc.mpmt.core.domain.port.TransportPort.class);
        FabricServerServices assembledServices = FabricServerServices.install(assembledRuntime);
        assembledRuntime.enable();
        runtime = assembledRuntime;
        transport = assembledTransport;
        services = assembledServices;
        ACTIVE_TRANSPORT.set(assembledTransport);
        ACTIVE_SERVICES.set(assembledServices);
        LOGGER.info("MPMT 已装配并启用，活跃平台：{}", PlatformProvider.get().platformId());
    }

    private void onPlayerConnected(ServerPlayer player) {
        FabricServerServices currentServices = services;
        FabricServerTransport currentTransport = transport;
        if (currentServices == null || currentTransport == null) {
            return;
        }
        FabricConnectionHandle connection = currentTransport.onConnected(player);
        currentServices.networkFeature().onConnected(connection);
    }

    private void onPlayerDisconnected(ServerPlayer player) {
        FabricServerServices currentServices = services;
        FabricServerTransport currentTransport = transport;
        if (currentServices == null || currentTransport == null) {
            return;
        }
        FabricConnectionHandle connection = currentTransport.onDisconnected(player);
        if (connection != null) {
            currentServices.networkFeature().onDisconnected(connection);
        }
    }

    private void onServerStopped(MinecraftServer server) {
        MpmtRuntime currentRuntime = runtime;
        if (currentRuntime != null) {
            FabricCapabilityBootstrap.clearRuntime(currentRuntime);
            // 关闭 Fabric 调度端口（多服 tick 路由注册表 + 异步池），避免停服后仍挂活动实例
            closeSchedulerPort(currentRuntime);
            if (currentRuntime.phase() == MpmtRuntime.Phase.ENABLED) {
                currentRuntime.disable();
            }
        }
        FabricServerTransport currentTransport = transport;
        if (currentTransport != null) {
            currentTransport.clearConnections();
        }
        runtime = null;
        services = null;
        transport = null;
        ACTIVE_SERVICES.set(null);
        ACTIVE_TRANSPORT.set(null);
        PlatformProvider.deactivate();
    }

    private static void closeSchedulerPort(MpmtRuntime currentRuntime) {
        try {
            SchedulerPort scheduler = currentRuntime.ports().get(SchedulerPort.class);
            if (scheduler instanceof AutoCloseable) {
                ((AutoCloseable) scheduler).close();
            }
        } catch (Exception e) {
            // 停服收尾：端口未装配或关闭失败不阻断 deactivate，但须可观测
            LOGGER.warn("关闭 Fabric 调度端口失败：{}", e.toString());
        }
    }

    /** 已启用的真实产品服务端网络特性，供验收场景驱动产品 API。 */
    public static ServerNetworkFeature serverNetworkFeature() {
        FabricServerServices current = ACTIVE_SERVICES.get();
        if (current == null) {
            throw new IllegalStateException("Fabric 服务端产品网络尚未启用");
        }
        return current.networkFeature();
    }

    /** 已启用的真实产品服务端传输，供验收取得当前在线连接句柄。 */
    public static FabricServerTransport serverTransport() {
        FabricServerTransport current = ACTIVE_TRANSPORT.get();
        if (current == null) {
            throw new IllegalStateException("Fabric 服务端传输尚未启用");
        }
        return current;
    }

    private BanService currentBanService() {
        FabricServerServices current = services;
        return current == null ? null : current.banService();
    }
}
