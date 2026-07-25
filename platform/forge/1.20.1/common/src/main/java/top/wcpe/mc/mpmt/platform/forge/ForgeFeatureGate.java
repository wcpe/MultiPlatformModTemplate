package top.wcpe.mc.mpmt.platform.forge;

import java.util.EnumSet;
import java.util.Set;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;

/**
 * Forge 能力探测：Forge 无 Folia 区域调度；同进程存在 Bukkit 即融合服；客户端发行环境内置集成服。
 *
 * <p>环境探测经 FMLEnvironment；在非 Forge 运行环境（如纯 JVM 测试）下不可用，保守判否。
 */
public final class ForgeFeatureGate implements FeatureGate {

    private final Set<Capability> supported;

    public ForgeFeatureGate() {
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        // Forge 无 Folia（REGION_SCHEDULER 否）
        if (classPresent("org.bukkit.Bukkit")) {
            set.add(Capability.HYBRID_FORGE_BUKKIT);
        }
        if (clientDist()) {
            set.add(Capability.INTEGRATED_SERVER);
        }
        this.supported = set;
    }

    @Override
    public boolean supports(Capability capability) {
        return supported.contains(capability);
    }

    /** 客户端发行环境内置集成服（单人世界）；专用服无。 */
    private static boolean clientDist() {
        try {
            return net.minecraftforge.fml.loading.FMLEnvironment.dist
                    == net.minecraftforge.api.distmarker.Dist.CLIENT;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, ForgeFeatureGate.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
