package top.wcpe.mc.mpmt.platform.fabric;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.client.ClientNetworkFeature;
import top.wcpe.mc.mpmt.core.client.DefaultMachineCodeProvider;
import top.wcpe.mc.mpmt.platform.fabric.client.FabricClientSession;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricNetworkBindings;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricVersions;
import top.wcpe.mc.mpmt.platform.fabric.version.SupportedVersion;

/**
 * Fabric 客户端入口（client）：按 tip 版本绑定建立每次 play 连接独立的产品会话。
 *
 * <p>JOIN 立即装配 S2C 收包；握手延到下一 tick（等 {@code minecraft:register} 出站后再发
 * ClientHello，避免 Paper 丢弃尚未登记监听通道上的 ServerHello）。自 5d3d79d 会话生命周期迁入。
 */
public final class MpmtFabricClientBootstrap implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    private static final AtomicReference<FabricClientSession> SESSION = new AtomicReference<>();

    @Override
    public void onInitializeClient() {
        SupportedVersion version = FabricVersions.detect();
        FabricClientSession clientSession =
                new FabricClientSession(
                        FabricNetworkBindings.clientNetwork(version),
                        modVersion(),
                        new DefaultMachineCodeProvider());
        SESSION.set(clientSession);

        AtomicBoolean handshakePending = new AtomicBoolean(false);
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    clientSession.join();
                    handshakePending.set(true);
                });
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    handshakePending.set(false);
                    clientSession.disconnect();
                });
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    if (!handshakePending.get()) {
                        return;
                    }
                    if (client.player == null || client.getConnection() == null) {
                        return;
                    }
                    if (!handshakePending.compareAndSet(true, false)) {
                        return;
                    }
                    clientSession.startHandshakeWhenReady();
                });
        LOGGER.info("MPMT Fabric 客户端网络已装配（mod 版本 {}）", modVersion());
    }

    /** 当前客户端产品会话，供验收验证真实产品状态。 */
    public static FabricClientSession session() {
        FabricClientSession current = SESSION.get();
        if (current == null) {
            throw new IllegalStateException("Fabric 客户端产品会话尚未初始化");
        }
        return current;
    }

    /**
     * 已启用的真实产品客户端网络特性，供验收断言握手 / 往返等产品状态。
     *
     * <p>须在 play 连接 JOIN 之后调用；未连接时失败快。
     */
    public static ClientNetworkFeature networkFeature() {
        ClientNetworkFeature feature = session().networkFeature();
        if (feature == null) {
            throw new IllegalStateException("Fabric 客户端产品网络尚未启用（未 JOIN play 连接）");
        }
        return feature;
    }

    private static String modVersion() {
        return FabricLoader.getInstance()
                .getModContainer("mpmt")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
