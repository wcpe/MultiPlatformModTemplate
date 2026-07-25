package top.wcpe.mc.mpmt.platform.bukkit;

import java.util.EnumSet;
import java.util.Set;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;

/**
 * Bukkit 家族能力探测：以"类是否存在"判定运行环境能力（capability detection，非平台嗅探）。
 *
 * <p>探测在构造期一次性完成、之后只读：Folia（区域调度）看 RegionizedServer 是否在类路径；
 * 融合服看 Forge 是否同进程存在；Bukkit 服为专用服，不具备集成服回环能力。
 */
public final class BukkitFeatureGate implements FeatureGate {

    /** Folia 标志类：存在即支持区域调度（ADR-0013）。 */
    private static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";

    /** Forge 标志类：与 Bukkit 同进程存在即为融合服（ADR-0008）。 */
    private static final String FORGE_MARKER = "net.minecraftforge.common.MinecraftForge";

    private final Set<Capability> supported;

    public BukkitFeatureGate() {
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        if (classPresent(FOLIA_MARKER)) {
            set.add(Capability.REGION_SCHEDULER);
        }
        if (classPresent(FORGE_MARKER)) {
            set.add(Capability.HYBRID_FORGE_BUKKIT);
        }
        // INTEGRATED_SERVER：Bukkit 服均为专用服，不支持
        this.supported = set;
    }

    @Override
    public boolean supports(Capability capability) {
        return supported.contains(capability);
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, BukkitFeatureGate.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
