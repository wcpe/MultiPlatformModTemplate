package top.wcpe.mc.mpmt.platform.forge.modern.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.wcpe.mc.mpmt.core.client.DefaultMachineCodeProvider;
import top.wcpe.mc.mpmt.platform.forge.modern.MpmtForge262Mod;

/** Forge 客户端连接生命周期桥接。 */
@Mod.EventBusSubscriber(modid = MpmtForge262Mod.MOD_ID, value = Dist.CLIENT)
public final class ForgeClientEvents {

    private static final ForgeClientSession SESSION =
            new ForgeClientSession(
                    MpmtForge262Mod.productChannel(),
                    MpmtForge262Mod.version(),
                    new DefaultMachineCodeProvider());

    private ForgeClientEvents() {
        // 事件订阅类不实例化
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        SESSION.join(event.getConnection());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SESSION.disconnect();
    }

    public static ForgeClientSession session() {
        return SESSION;
    }
}
