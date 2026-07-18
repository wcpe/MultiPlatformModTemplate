package top.wcpe.mc.mpmt.platform.sponge;

import com.google.inject.Inject;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Server;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.platform.sponge.capability.SpongeCapabilityBootstrap;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeConnectionHandle;
import top.wcpe.mc.mpmt.platform.sponge.net.SpongeServerTransport;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * Sponge 插件入口（{@code @Plugin}）：经 Sponge 生命周期事件驱动平台装配。
 *
 * <p>分两步契合 Sponge 生命周期：① 构造期（{@link ConstructPluginEvent}）经本插件类加载器 ServiceLoader 发现并
 * 装配唯一活跃平台（绑定，未启用）；② 通道注册期（{@link RegisterChannelEvent}）注册 {@code mpmt:main}
 * {@link RawDataChannel}、注入服务端 {@link TransportPort}、登记平台无关服务端网络特性（FR-19）后启用运行时，
 * 再装配平台能力示例（FR-26/FR-27）。通道注册只在该生命周期事件可做，故 enable 推迟到此刻（先有传输再启用特性）。
 *
 * <p>停服（{@link StoppingEngineEvent}）时停用运行时并释放进程级平台绑定（FR-25 / ADR-0008）。
 * 用本类的类加载器（Sponge 插件类加载器）做 ServiceLoader 发现，确保扫到本 jar 的 services（ADR-0002 注意项）。
 */
@Plugin("mpmt")
public final class MpmtSpongePlugin {

    /** 产品跨端通道（namespace:path），须与各平台一致（Fabric/Bukkit 亦 {@code mpmt:main}）以支持异构互通。 */
    private static final ResourceKey CHANNEL_KEY = ResourceKey.of("mpmt", "main");

    private final Logger logger;
    private final PluginContainer container;
    private final Path configDir;

    private MpmtRuntime runtime;
    private ServerNetworkFeature serverNetworkFeature;

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
        // 通用装配：发现并装配唯一活跃平台（进程级单一活跃绑定见 ADR-0008 / FR-25）；此刻尚未启用
        PlatformProvider.boot(getClass().getClassLoader(), runtime);
        logger.info("MPMT 平台已绑定，活跃平台：{}（待通道注册后启用）", PlatformProvider.get().platformId());
    }

    @Listener
    public void onRegisterChannels(final RegisterChannelEvent event) {
        RawDataChannel channel = event.register(CHANNEL_KEY, RawDataChannel.class);
        // 服务端 TransportPort（FR-20）：用 RawDataChannel 收发裸字节
        runtime.ports().register(TransportPort.class, new SpongeServerTransport(channel, container));
        // 保存平台无关服务端网络特性，供只读验收接缝观察握手状态
        serverNetworkFeature = new ServerNetworkFeature(new BanRegistry(), () -> UUID.randomUUID().toString());
        runtime.features().register(serverNetworkFeature);
        runtime.enable();
        // 平台能力示例（FR-26/FR-27）：装配 L3 端口 + 桥接玩家进退事件入运行时自有 EventBus
        SpongeCapabilityBootstrap.register(container, configDir, runtime.eventBus());
        logger.info("MPMT 已启用，活跃平台：{}", PlatformProvider.get().platformId());
    }

    /** 查询指定玩家连接的握手状态；尚无会话时返回 null。 */
    public HandshakeStateMachine.State handshakeState(UUID playerId) {
        ServerNetworkFeature feature = this.serverNetworkFeature;
        if (feature == null) {
            throw new IllegalStateException("服务端网络特性尚未注册");
        }
        return feature.handshakeService().stateOf(new SpongeConnectionHandle(playerId));
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
