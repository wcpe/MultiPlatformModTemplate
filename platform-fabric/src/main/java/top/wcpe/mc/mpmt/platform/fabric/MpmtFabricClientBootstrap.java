package top.wcpe.mc.mpmt.platform.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.client.ClientNetworkFeature;
import top.wcpe.mc.mpmt.core.client.DefaultMachineCodeProvider;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.fabric.capability.FabricHudRenderer;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricClientTransport;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricNetworkBindings;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricVersions;
import top.wcpe.mc.mpmt.platform.fabric.version.SupportedVersion;

/**
 * Fabric 客户端入口（client，仅客户端发行环境）：装配客户端网络栈。
 *
 * <p>与服务端装配（{@link MpmtFabricBootstrap} 经 {@code PlatformProvider}）分离——客户端侧独立运行时：
 * 探测版本 → 客户端网络绑定 → 客户端 {@link TransportPort} → 登记客户端网络特性 → 连入服务端时发起握手。
 * 单人世界下二者并存（集成服走服务端栈、客户端走客户端栈），与 MC 客户端 + 集成服并存的模型一致。
 */
public final class MpmtFabricClientBootstrap implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    @Override
    public void onInitializeClient() {
        SupportedVersion version = FabricVersions.detect();
        FabricClientTransport transport =
                new FabricClientTransport(FabricNetworkBindings.clientNetwork(version));
        MpmtRuntime runtime = new MpmtRuntime();
        runtime.ports().register(TransportPort.class, transport);
        ClientNetworkFeature feature =
                new ClientNetworkFeature(modVersion(), new DefaultMachineCodeProvider());
        runtime.features().register(feature);
        runtime.enable();
        // 跨端 HUD 渲染（FR-27）：在客户端收发管线上注册 HUD 收包处理器
        FabricHudRenderer.register(feature.dispatcher());
        // 客户端连入服务端后发起握手（连接事件在客户端线程触发）
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> feature.startHandshake());
        LOGGER.info("MPMT Fabric 客户端网络已装配（mod 版本 {}）", modVersion());
    }

    /** 本 mod 自身版本（握手时上报给服务端，信息用途）。 */
    private static String modVersion() {
        return FabricLoader.getInstance()
                .getModContainer("mpmt")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
