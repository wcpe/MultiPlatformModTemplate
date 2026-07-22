package top.wcpe.mc.mpmt.platform.sponge;

import com.google.inject.Inject;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;
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
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionHandle;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionRegistry;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeServerTransport;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * Sponge 插件入口（{@code @Plugin}）：经 Sponge 生命周期事件驱动平台装配。
 *
 * <p>分两步契合 Sponge 生命周期：① 构造期（{@link ConstructPluginEvent}）创建运行时；② 通道注册期
 * （{@link RegisterChannelEvent}）把注册事件、插件容器与配置目录交给 SPI；SPI 探测实际 MC 版本，
 * 由对应 L4 adapter 注册 {@code mpmt:main} 通道并装配服务端端口。
 *
 * <p>停服（{@link StoppingEngineEvent}）时停用运行时并释放进程级平台绑定（FR-25 / ADR-0008）。
 * 用本类的类加载器（Sponge 插件类加载器）做 ServiceLoader 发现，确保扫到本 jar 的 services（ADR-0002 注意项）。
 */
@Plugin("mpmt")
public final class MpmtSpongePlugin {

    private final Logger logger;
    private final PluginContainer container;
    private final Path configDir;

    private MpmtRuntime runtime;
    private ServerNetworkFeature serverNetworkFeature;
    private BanService banService;
    private SchedulerPort scheduler;

    @Inject
    MpmtSpongePlugin(
            final Logger logger,
            final PluginContainer container,
            @ConfigDir(sharedRoot = false) final Path configDir) {
        this.logger = logger;
        this.container = container;
        this.configDir = configDir;
    }

    @Listener
    public void onConstruct(final ConstructPluginEvent event) {
        runtime = new MpmtRuntime();
        logger.info("MPMT 运行时已创建，待通道注册后装配平台端口");
    }

    @Listener
    public void onRegisterChannels(final RegisterChannelEvent event) {
        PlatformAssemblyContext context =
                new PlatformAssemblyContext()
                        .register(PluginContainer.class, container)
                        .register(Path.class, configDir)
                        .register(RegisterChannelEvent.class, event);
        PlatformProvider.boot(getClass().getClassLoader(), runtime, context);
        BanRegistry banRegistry = new BanRegistry();
        SessionRegistry sessions = new SessionRegistry();
        serverNetworkFeature =
                new ServerNetworkFeature(
                        banRegistry, () -> UUID.randomUUID().toString(), sessions);
        runtime.features().register(serverNetworkFeature);
        runtime.enable();
        wireServerServices(banRegistry, sessions);
        logger.info("MPMT 已启用，活跃平台：{}", PlatformProvider.get().platformId());
    }

    private void wireServerServices(BanRegistry banRegistry, SessionRegistry sessions) {
        scheduler = runtime.ports().get(SchedulerPort.class);
        ConnectionControlPort connectionControl =
                runtime.ports().get(ConnectionControlPort.class);
        banService =
                new BanService(
                        banRegistry,
                        sessions,
                        runtime.ports().get(PersistencePort.class),
                        scheduler,
                        connectionControl);
        SpongeConnectionRegistry connections =
                runtime.ports().get(SpongeConnectionRegistry.class);
        Sponge.eventManager()
                .registerListeners(
                        container,
                        new SpongeServerConnectionListener(serverNetworkFeature, connections));
        SpongeServerTransport transport =
                (SpongeServerTransport) runtime.ports().get(TransportPort.class);
        transport.onHandled(
                connection -> disconnectIfRejected(
                        serverNetworkFeature, scheduler, connectionControl, connection));
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
            logger.info("机器码封禁持久化已异步加载");
        } else {
            logger.error("机器码封禁持久化加载失败：{}", error.getMessage());
        }
    }

    @Listener
    public void onRegisterCommands(final RegisterCommandEvent<Command.Parameterized> event) {
        event.register(
                container,
                SpongeMachineCodeCommand.create(() -> banService, () -> scheduler),
                "mpmt");
    }

    /** 查询指定玩家连接的握手状态；尚无会话时返回 null。 */
    public HandshakeStateMachine.State handshakeState(UUID playerId) {
        ServerNetworkFeature feature = this.serverNetworkFeature;
        if (feature == null) {
            throw new IllegalStateException("服务端网络特性尚未注册");
        }
        SpongeConnectionHandle connection =
                runtime.ports().get(SpongeConnectionRegistry.class).current(playerId);
        return connection == null ? null : feature.handshakeService().stateOf(connection);
    }

    /** 返回插件数据基目录的只读路径接缝，供验收场景观察平台持久化结果。 */
    public Path dataDirectory() {
        return configDir;
    }

    @Listener
    public void onStopping(final StoppingEngineEvent<Server> event) {
        if (runtime != null && runtime.phase() == MpmtRuntime.Phase.ENABLED) {
            runtime.disable();
        }
        // 释放进程级平台绑定，使同 JVM 内重启能再次 boot（FR-25 / ADR-0008）
        PlatformProvider.deactivate();
    }
}
