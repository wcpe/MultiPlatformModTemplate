package top.wcpe.mc.mpmt.platform.forge;

import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkCheckHandler;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import top.wcpe.mc.mpmt.core.client.DefaultMachineCodeProvider;
import top.wcpe.mc.mpmt.platform.forge.client.ForgeClientSession;
import top.wcpe.mc.mpmt.platform.forge.hud.ForgeHudAdapter;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeClientTransport;

/** Forge 1.12.2 client-only 产品入口。 */
@Mod(
        modid = MpmtForgeMod.MOD_ID,
        name = "MultiPlatformModTemplate Client",
        version = ForgeBuildInfo.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        acceptableRemoteVersions = "*",
        clientSideOnly = true,
        useMetadata = true)
public final class MpmtForgeMod {

    public static final String MOD_ID = "mpmt";
    public static final String PRODUCT_CHANNEL = "MPMT";

    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private static volatile ForgeClientSession activeSession;
    private static volatile boolean connected;
    private static volatile boolean optionalCheckAccepted;
    private static volatile boolean remoteForgeProductAbsent;

    private ForgeClientSession session;
    private volatile boolean joinStarted;

    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        ForgeClientTransport transport = new ForgeClientTransport(PRODUCT_CHANNEL);
        session =
                new ForgeClientSession(
                        transport,
                        new ForgeHudAdapter(),
                        ForgeBuildInfo.VERSION,
                        new DefaultMachineCodeProvider());
        activeSession = session;
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("MPMT Forge 1.12.2 客户端产品已初始化，等待进入服务端后启动握手");
    }

    /** 允许连接未安装我方 Forge mod 的服务端。 */
    @NetworkCheckHandler
    public boolean acceptRemoteVersions(Map<String, String> remoteVersions, Side remoteSide) {
        if (remoteSide == Side.SERVER) {
            optionalCheckAccepted = true;
            remoteForgeProductAbsent = !remoteVersions.containsKey(MOD_ID);
        }
        return true;
    }

    /** 网络连接建立后只记录状态，等待玩家世界就绪。 */
    @SubscribeEvent
    public void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        joinStarted = false;
        connected = true;
        LOGGER.info("客户端网络已连接，等待玩家世界就绪后开始 MPMT 产品握手");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        handleClientTick(event.phase, Minecraft.getMinecraft().player != null);
    }

    private void handleClientTick(TickEvent.Phase phase, boolean playerReady) {
        if (phase != TickEvent.Phase.END || !connected || !playerReady || joinStarted) {
            return;
        }
        joinStarted = true;
        session.join();
        LOGGER.info("客户端玩家世界已就绪，开始 MPMT 产品握手");
    }

    @SubscribeEvent
    public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        connected = false;
        joinStarted = false;
        optionalCheckAccepted = false;
        remoteForgeProductAbsent = false;
        session.disconnect();
        LOGGER.info("客户端已离开服务端，清理 MPMT 产品会话");
    }

    /** 当前产品会话，供独立验收伴侣读取真实产品状态。 */
    public static ForgeClientSession session() {
        ForgeClientSession current = activeSession;
        if (current == null) {
            throw new IllegalStateException("1.12.2 产品会话尚未初始化");
        }
        return current;
    }

    public static boolean isConnected() {
        return connected;
    }

    public static boolean optionalCheckAccepted() {
        return optionalCheckAccepted;
    }

    public static boolean remoteForgeProductAbsent() {
        return remoteForgeProductAbsent;
    }
}
