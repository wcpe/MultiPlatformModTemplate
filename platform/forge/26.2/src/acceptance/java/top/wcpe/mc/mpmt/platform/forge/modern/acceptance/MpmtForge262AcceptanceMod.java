package top.wcpe.mc.mpmt.platform.forge.modern.acceptance;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeTypedPayloadChannel;

/** Forge 26.2 独立验收 mod 入口。 */
@Mod(MpmtForge262AcceptanceMod.MOD_ID)
public final class MpmtForge262AcceptanceMod {

    public static final String MOD_ID = "mpmt_acceptance";
    public static final Identifier CONTROL_CHANNEL =
            Identifier.fromNamespaceAndPath("mpmt-test", "acceptance");

    private static final ForgeAcceptanceControlChannel CONTROL =
            new ForgeAcceptanceControlChannel(new ForgeTypedPayloadChannel(CONTROL_CHANNEL));

    public MpmtForge262AcceptanceMod() {
        CONTROL.registerServerReceiver();
        // EventBus 7：监听器改由各事件类型自带的静态 BUS 注册
        ServerStartedEvent.BUS.addListener(this::onServerStarted);
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(this::onPlayerLoggedOut);
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
