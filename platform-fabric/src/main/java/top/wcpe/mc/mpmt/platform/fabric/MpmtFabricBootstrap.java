package top.wcpe.mc.mpmt.platform.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.platform.fabric.command.FabricMachineCodeCommands;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricConnectionHandle;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricServerTransport;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/** Fabric 主入口：装配服务端闭环、原生命令与玩家物理连接生命周期。 */
public final class MpmtFabricBootstrap implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

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
        if (currentRuntime != null && currentRuntime.phase() == MpmtRuntime.Phase.ENABLED) {
            currentRuntime.disable();
        }
        FabricServerTransport currentTransport = transport;
        if (currentTransport != null) {
            currentTransport.clearConnections();
        }
        runtime = null;
        services = null;
        transport = null;
        PlatformProvider.deactivate();
    }

    private BanService currentBanService() {
        FabricServerServices current = services;
        return current == null ? null : current.banService();
    }
}
