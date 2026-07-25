package top.wcpe.mc.mpmt.platform.neoforge.proxy;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.client.ClientNetworkFeature;
import top.wcpe.mc.mpmt.core.client.DefaultMachineCodeProvider;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeClientHudReceiver;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeClientTransport;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeServerTransport;

/** NeoForge 客户端代理：装配统一客户端网络特性、HUD、握手与断线清理。 */
public final class ClientProxy implements SidedProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    /** 当前客户端产品网络特性，供验收验证真实产品状态（非产品业务入口）。 */
    private static final AtomicReference<ClientNetworkFeature> ACTIVE_FEATURE = new AtomicReference<>();

    private final NeoForgeServerTransport serverTransport;
    private ClientNetworkFeature feature;
    private NeoForgeClientTransport transport;

    public ClientProxy(NeoForgeServerTransport serverTransport) {
        this.serverTransport = Objects.requireNonNull(serverTransport, "serverTransport 不能为空");
    }

    @Override
    public void init() {
        transport = new NeoForgeClientTransport(serverTransport);
        MpmtRuntime runtime = new MpmtRuntime();
        runtime.ports().register(TransportPort.class, transport);
        feature = new ClientNetworkFeature(modVersion(), new DefaultMachineCodeProvider());
        runtime.features().register(feature);
        runtime.enable();
        ACTIVE_FEATURE.set(feature);
        NeoForgeClientHudReceiver.register(feature.dispatcher());
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("MPMT NeoForge 客户端网络已装配（mod 版本 {}）", modVersion());
    }

    /** 客户端进入 PLAY 阶段后发起握手。 */
    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        feature.startHandshake();
    }

    /** 客户端断线时清理唯一 dispatcher 的连接可靠性状态。 */
    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        feature.dispatcher().onDisconnected(transport.serverConnection());
    }

    /** 已启用的真实产品客户端网络特性，供验收断言握手 / 往返等产品状态。 */
    public static ClientNetworkFeature networkFeature() {
        ClientNetworkFeature current = ACTIVE_FEATURE.get();
        if (current == null) {
            throw new IllegalStateException("NeoForge 客户端产品网络尚未启用");
        }
        return current;
    }

    private static String modVersion() {
        return ModList.get()
                .getModContainerById("mpmt")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }
}
