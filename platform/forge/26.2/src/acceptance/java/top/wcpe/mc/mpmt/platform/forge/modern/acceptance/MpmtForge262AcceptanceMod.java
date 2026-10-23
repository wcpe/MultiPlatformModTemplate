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

    /**
     * 取验收控制通道单例。
     *
     * <p>刻意返回内部单例而非拷贝：服务端侧与客户端伴侣必须**共用同一条通道实例**，
     * 才能在进程内共享收发状态（见 {@code ForgeAcceptanceClientCompanion} 注册接收器）。
     * 该通道是验收专用对象、只在验收运行时存在，不进入产品路径。
     *
     * <p>SpotBugs 会就此报 {@code EI_EXPOSE_STATIC_REP2}；该告警属设计取舍而非缺陷，
     * 且本工程 SpotBugs 配置为 ignoreFailures，不阻塞构建。
     */
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
