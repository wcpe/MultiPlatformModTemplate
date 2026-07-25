package top.wcpe.mc.mpmt.platform.forge.acceptance;

import java.util.Map;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.network.NetworkCheckHandler;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import top.wcpe.mc.mpmt.platform.forge.ForgeBuildInfo;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeClientTransport;

/** Forge 1.12.2 独立 client-only 验收伴侣入口。 */
@Mod(
        modid = MpmtForgeAcceptanceMod.MOD_ID,
        name = "MPMT Acceptance Companion",
        version = ForgeBuildInfo.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        acceptableRemoteVersions = "*",
        dependencies = "required-after:mpmt",
        clientSideOnly = true,
        useMetadata = true)
public final class MpmtForgeAcceptanceMod {

    public static final String MOD_ID = "mpmt_acceptance";
    public static final String ACCEPTANCE_CHANNEL = "MPMTTEST";

    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        ForgeAcceptanceCompanion companion =
                new ForgeAcceptanceCompanion(new ForgeClientTransport(ACCEPTANCE_CHANNEL));
        MinecraftForge.EVENT_BUS.register(companion);
        LOGGER.info("MPMT Forge 1.12.2 客户端验收伴侣已初始化");
    }

    /** 验收伴侣同样不得要求服务端安装 Forge 验收 mod。 */
    @NetworkCheckHandler
    public boolean acceptRemoteVersions(Map<String, String> remoteVersions, Side remoteSide) {
        return true;
    }
}
