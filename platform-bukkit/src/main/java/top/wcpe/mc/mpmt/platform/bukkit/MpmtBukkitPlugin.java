package top.wcpe.mc.mpmt.platform.bukkit;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.core.server.SessionRegistry;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionRegistry;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitServerTransport;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * Bukkit 家族插件入口：进服后驱动平台装配——构造运行时、经本插件类加载器发现并装配唯一活跃平台、启用特性。
 *
 * <p>用本插件类加载器（PluginClassLoader）做 ServiceLoader 发现，确保扫到本 jar 内的 services（ADR-0002 注意项）。
 */
public class MpmtBukkitPlugin extends JavaPlugin {

    private MpmtRuntime runtime;
    private BanService banService;

    @Override
    public void onEnable() {
        runtime = new MpmtRuntime();
        PlatformAssemblyContext context =
                new PlatformAssemblyContext().register(org.bukkit.plugin.Plugin.class, this);
        PlatformProvider.boot(getClass().getClassLoader(), runtime, context);
        BanRegistry banRegistry = new BanRegistry();
        SessionRegistry sessions = new SessionRegistry();
        ServerNetworkFeature networkFeature =
                new ServerNetworkFeature(
                        banRegistry, () -> UUID.randomUUID().toString(), sessions);
        runtime.features().register(networkFeature);
        runtime.enable();
        wireServerServices(banRegistry, sessions, networkFeature);
        // 融合服感知（FR-25 / ADR-0008）：CatServer 等 Forge+Bukkit 同进程时，以 Bukkit 入口绑定为唯一活跃平台、
        // 不激活我方 Forge 入口（我方多入口同进程同时激活会在 PlatformProvider.boot 失败快）。
        if (PlatformProvider.get().featureGate().supports(Capability.HYBRID_FORGE_BUKKIT)) {
            getLogger()
                    .info("检测到 Forge+Bukkit 融合服（CatServer 等），以 Bukkit 入口绑定为唯一活跃平台（ADR-0008）");
        }
        getLogger().info("MPMT 已装配并启用，活跃平台：" + PlatformProvider.get().platformId());
    }

    private void wireServerServices(
            BanRegistry banRegistry,
            SessionRegistry sessions,
            ServerNetworkFeature networkFeature) {
        SchedulerPort scheduler = runtime.ports().get(SchedulerPort.class);
        ConnectionControlPort connectionControl =
                runtime.ports().get(ConnectionControlPort.class);
        banService =
                new BanService(
                        banRegistry,
                        sessions,
                        runtime.ports().get(PersistencePort.class),
                        scheduler,
                        connectionControl);
        BukkitConnectionRegistry connections =
                runtime.ports().get(BukkitConnectionRegistry.class);
        getServer()
                .getPluginManager()
                .registerEvents(new BukkitServerConnectionListener(networkFeature, connections), this);
        BukkitServerTransport transport =
                (BukkitServerTransport) runtime.ports().get(TransportPort.class);
        transport.onHandled(
                connection -> disconnectIfRejected(
                        networkFeature, scheduler, connectionControl, connection));
        PluginCommand command = Objects.requireNonNull(getCommand("mpmt"), "plugin.yml 缺少 mpmt 命令");
        command.setExecutor(new BukkitMachineCodeCommand(banService, scheduler, getServer()));
        banService.initialize().whenComplete((ignored, error) -> logInitialization(error));
    }

    private static void disconnectIfRejected(
            ServerNetworkFeature networkFeature,
            SchedulerPort scheduler,
            ConnectionControlPort connectionControl,
            ConnectionHandle connection) {
        if (networkFeature.handshakeService().stateOf(connection)
                != HandshakeStateMachine.State.REJECTED) {
            return;
        }
        scheduler.runForEntity(
                connectionControl.entityOf(connection),
                () -> {
                    if (networkFeature.handshakeService().stateOf(connection)
                            == HandshakeStateMachine.State.REJECTED) {
                        connectionControl.disconnect(connection, "你的客户端标识已被封禁");
                    }
                });
    }

    private void logInitialization(Throwable error) {
        if (error == null) {
            getLogger().info("机器码封禁持久化已异步加载");
        } else {
            getLogger().severe("机器码封禁持久化加载失败：" + error.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (runtime != null && runtime.phase() == MpmtRuntime.Phase.ENABLED) {
            runtime.disable();
        }
        // 释放进程级平台绑定，使同 JVM 内重新启用（/reload）能再次 boot（FR-25 / ADR-0008）
        PlatformProvider.deactivate();
    }
}
