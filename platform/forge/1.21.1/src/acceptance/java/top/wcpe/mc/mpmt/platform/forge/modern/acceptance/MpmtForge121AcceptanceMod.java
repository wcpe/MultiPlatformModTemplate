package top.wcpe.mc.mpmt.platform.forge.modern.acceptance;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeTypedPayloadChannel;

/** Forge 1.21.1 独立验收 mod 入口。 */
@Mod(MpmtForge121AcceptanceMod.MOD_ID)
public final class MpmtForge121AcceptanceMod {

    public static final String MOD_ID = "mpmt_acceptance";
    public static final ResourceLocation CONTROL_CHANNEL =
            ResourceLocation.fromNamespaceAndPath("mpmt-test", "acceptance");

    private static final ForgeAcceptanceControlChannel CONTROL =
            new ForgeAcceptanceControlChannel(new ForgeTypedPayloadChannel(CONTROL_CHANNEL));

    public MpmtForge121AcceptanceMod() {
        CONTROL.registerServerReceiver();
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
    }

    public static ForgeAcceptanceControlChannel control() {
        return CONTROL;
    }

    private void onServerStarted(ServerStartedEvent event) {
        if ("true".equals(System.getProperty("mpmt.acceptance"))) {
            CONTROL.bindServer(event.getServer());
            AcceptanceDriver.start(event.getServer(), CONTROL.client());
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CONTROL.onDisconnected(player);
        }
    }
}
