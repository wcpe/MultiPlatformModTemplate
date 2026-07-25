package top.wcpe.mc.mpmt.platform.bukkit;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.core.server.SessionRegistry;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionHandle;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionRegistry;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitServerTransport;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;

/**
 * Bukkit 家族插件入口：进服后驱动平台装配——构造运行时、经本插件类加载器发现并装配唯一活跃平台、启用特性。
 *
 * <p>用本插件类加载器（PluginClassLoader）做 ServiceLoader 发现，确保扫到本 jar 内的 services（ADR-0002 注意项）。
 */
public class MpmtBukkitPlugin extends JavaPlugin {

    /** 当前启用的产品插件实例，供验收场景驱动真实产品 API（非产品业务入口）。 */
    private static final AtomicReference<MpmtBukkitPlugin> ACTIVE = new AtomicReference<>();

    private MpmtRuntime runtime;
    private BanService banService;
    private ServerNetworkFeature networkFeature;
    private BukkitConnectionRegistry connections;
    private SchedulerPort schedulerPort;

    @Override
    public void onEnable() {
        runtime = new MpmtRuntime();
        PlatformAssemblyContext context =
                new PlatformAssemblyContext().register(org.bukkit.plugin.Plugin.class, this);
        PlatformProvider.boot(getClass().getClassLoader(), runtime, context);
        BanRegistry banRegistry = new BanRegistry();
        SessionRegistry sessions = new SessionRegistry();
        ServerNetworkFeature assembledNetwork =
                new ServerNetworkFeature(
                        banRegistry, () -> UUID.randomUUID().toString(), sessions);
        runtime.features().register(assembledNetwork);
        runtime.enable();
        wireServerServices(banRegistry, sessions, assembledNetwork);
        ACTIVE.set(this);
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
            ServerNetworkFeature assembledNetwork) {
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
        BukkitConnectionRegistry connectionRegistry =
                runtime.ports().get(BukkitConnectionRegistry.class);
        networkFeature = assembledNetwork;
        connections = connectionRegistry;
        schedulerPort = scheduler;
        getServer()
                .getPluginManager()
                .registerEvents(
                        new BukkitServerConnectionListener(assembledNetwork, connectionRegistry), this);
        BukkitServerTransport transport =
                (BukkitServerTransport) runtime.ports().get(TransportPort.class);
        transport.onHandled(
                connection -> disconnectIfRejected(
                        assembledNetwork, scheduler, connectionControl, connection));
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
        ACTIVE.compareAndSet(this, null);
        networkFeature = null;
        connections = null;
        schedulerPort = null;
        banService = null;
    }

    /** 已启用的真实产品插件，供验收场景驱动产品 API。 */
    public static MpmtBukkitPlugin product() {
        MpmtBukkitPlugin current = ACTIVE.get();
        if (current == null) {
            throw new IllegalStateException("Bukkit 产品插件尚未启用");
        }
        return current;
    }

    /** 已启用的真实产品服务端网络特性。 */
    public ServerNetworkFeature serverNetworkFeature() {
        if (networkFeature == null) {
            throw new IllegalStateException("Bukkit 服务端产品网络尚未启用");
        }
        return networkFeature;
    }

    /** 取与产品栈一致的物理连接句柄（须经登记表，勿自行 new）。 */
    public BukkitConnectionHandle connectionFor(Player player) {
        if (connections == null) {
            throw new IllegalStateException("Bukkit 连接登记表尚未装配");
        }
        return connections.handleOf(Objects.requireNonNull(player, "player 不能为空"));
    }

    /** 经真实产品 HudMessageService 向指定玩家下发 ACTIONBAR HUD。 */
    public void sendActionBarHud(Player player, String text) {
        serverNetworkFeature()
                .hudMessageService()
                .send(
                        connectionFor(player),
                        HudKind.ACTIONBAR,
                        Objects.requireNonNull(text, "HUD 文本不能为空"));
    }

    /** 产品实际装配的调度端口。 */
    public SchedulerPort schedulerPort() {
        if (schedulerPort == null) {
            throw new IllegalStateException("Bukkit 调度端口尚未装配");
        }
        return schedulerPort;
    }

    /** 当前活跃平台 id（验收 R5 断言唯一绑定）。 */
    public String activePlatformId() {
        return PlatformProvider.get().platformId();
    }

    /** 产品 FeatureGate 是否启用 Forge+Bukkit 融合服能力（验收 R5）。 */
    public boolean isHybridForgeBukkit() {
        return PlatformProvider.get().featureGate().supports(Capability.HYBRID_FORGE_BUKKIT);
    }

    /**
     * 调度端口实现类全名（验收 R6 断言 Folia 选型）。
     *
     * <p>验收 jar 与产品 jar 分装（ADR-0014），跨插件类加载器不可直接强转 {@link SchedulerPort}，
     * 故暴露类名与下方 primitive 调度入口供反射桥调用。
     */
    public String schedulerPortClassName() {
        return schedulerPort().getClass().getName();
    }

    /** 经产品实际调度端口执行全局任务（验收 R6）。 */
    public void runGlobalSchedulerTask(Runnable task) {
        schedulerPort().runGlobal(Objects.requireNonNull(task, "task 不能为空"));
    }

    /** 经产品实际实体调度入口执行任务（验收 R6）。 */
    public void runEntitySchedulerTask(UUID entityId, Runnable task) {
        schedulerPort()
                .runForEntity(
                        new EntityRef(Objects.requireNonNull(entityId, "entityId 不能为空")),
                        Objects.requireNonNull(task, "task 不能为空"));
    }

    /** 经产品实际区域调度入口执行任务（验收 R6）。 */
    public void runLocationSchedulerTask(String worldId, int x, int z, Runnable task) {
        schedulerPort()
                .runForLocation(
                        new WorldRef(Objects.requireNonNull(worldId, "worldId 不能为空")),
                        x,
                        z,
                        Objects.requireNonNull(task, "task 不能为空"));
    }
}
