package top.wcpe.mc.mpmt.platform.forge.modern;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeConnectionHandle;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeServerTransport;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeTypedPayloadChannel;

/** Forge 1.21.1 产品入口，同时装配服务端产品网络栈。 */
@Mod(MpmtForge121Mod.MOD_ID)
public final class MpmtForge121Mod {

    public static final String MOD_ID = "mpmt";
    public static final ResourceLocation PRODUCT_CHANNEL =
            ResourceLocation.fromNamespaceAndPath("mpmt", "main");

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");
    private static final ForgeTypedPayloadChannel CHANNEL =
            new ForgeTypedPayloadChannel(PRODUCT_CHANNEL);
    private static final ServerNetworkFeature SERVER_NETWORK_FEATURE = createServerNetwork();

    public MpmtForge121Mod() {
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        LOGGER.info("产品网络已启用：平台=Forge 1.21.1，通道={}", PRODUCT_CHANNEL);
    }

    public static ForgeTypedPayloadChannel productChannel() {
        return CHANNEL;
    }

    public static ServerNetworkFeature serverNetworkFeature() {
        return SERVER_NETWORK_FEATURE;
    }

    public static String version() {
        String implementationVersion = MpmtForge121Mod.class.getPackage().getImplementationVersion();
        return implementationVersion == null ? "0.1.0" : implementationVersion;
    }

    private static ServerNetworkFeature createServerNetwork() {
        MpmtRuntime runtime = new MpmtRuntime();
        ForgeServerTransport transport = new ForgeServerTransport(CHANNEL);
        ServerNetworkFeature feature =
                new ServerNetworkFeature(new BanRegistry(), () -> UUID.randomUUID().toString());
        runtime.ports().register(TransportPort.class, transport);
        runtime.features().register(feature);
        runtime.enable();
        return feature;
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SERVER_NETWORK_FEATURE.onDisconnected(new ForgeConnectionHandle(player));
        }
    }
}
