package top.wcpe.mc.mpmt.platform.forge.modern;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.platform.forge.modern.capability.ForgeCapabilityBootstrap;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeServerTransport;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeTypedPayloadChannel;

/** Forge 26.2 产品入口：在服务端启动事件中装配能力端口与产品网络栈。 */
@Mod(MpmtForge262Mod.MOD_ID)
public final class MpmtForge262Mod {

    public static final String MOD_ID = "mpmt";
    public static final Identifier PRODUCT_CHANNEL =
            Identifier.fromNamespaceAndPath("mpmt", "main");

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");
    private static final ForgeTypedPayloadChannel CHANNEL =
            new ForgeTypedPayloadChannel(PRODUCT_CHANNEL);

    /** 服务端产品闭环，由 ServerStartedEvent 装配、ServerStoppedEvent 清除。 */
    private static final AtomicReference<ForgeServerServices> ACTIVE_SERVICES = new AtomicReference<>();
    private static volatile MpmtRuntime activeRuntime;

    private final ForgeServerTransport transport;

    public MpmtForge262Mod() {
        transport = new ForgeServerTransport(CHANNEL);
        // EventBus 7：监听器改由各事件类型自带的静态 BUS 注册
        ServerStartedEvent.BUS.addListener(this::onServerStarted);
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(this::onPlayerLoggedIn);
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(this::onPlayerLoggedOut);
        ServerStoppedEvent.BUS.addListener(this::onServerStopped);
        LOGGER.info("产品网络通道已就绪：平台=Forge 26.2，通道={}", PRODUCT_CHANNEL);
    }

    public static ForgeTypedPayloadChannel productChannel() {
        return CHANNEL;
    }

    /** 已启用的真实产品服务端网络特性；服务端未启动时抛异常。 */
    public static ServerNetworkFeature serverNetworkFeature() {
        ForgeServerServices current = ACTIVE_SERVICES.get();
        if (current == null) {
            throw new IllegalStateException("Forge 26.2 服务端产品网络尚未启用");
        }
        return current.networkFeature();
    }

    public static String version() {
        String impl = MpmtForge262Mod.class.getPackage().getImplementationVersion();
        return impl == null ? "0.1.0" : impl;
    }

    /** 服务端已启动：在接收玩家前完成端口、封禁服务与网络特性的装配。 */
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        MpmtRuntime runtime = new MpmtRuntime();
        runtime.ports().register(TransportPort.class, transport);
        ForgeCapabilityBootstrap.register(server, runtime);
        ForgeServerServices services = ForgeServerServices.install(runtime);
        runtime.enable();
        activeRuntime = runtime;
        ACTIVE_SERVICES.set(services);
        LOGGER.info("MPMT 已装配并启用，活跃平台：forge");
    }

    /** 玩家进入 PLAY 阶段时登记唯一物理连接，启动服务端握手流程。 */
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ForgeServerServices services = ACTIVE_SERVICES.get();
            if (services != null) {
                services.networkFeature().onConnected(transport.onConnected(player));
            }
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ForgeServerServices services = ACTIVE_SERVICES.get();
            if (services != null) {
                services.networkFeature().onDisconnected(transport.onConnected(player));
                transport.onDisconnected(player);
            }
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private void onServerStopped(ServerStoppedEvent event) {
        MpmtRuntime current = activeRuntime;
        if (current != null) {
            closeSchedulerPort(current);
            if (current.phase() == MpmtRuntime.Phase.ENABLED) {
                current.disable();
            }
        }
        transport.clearConnections();
        activeRuntime = null;
        ACTIVE_SERVICES.set(null);
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
}
