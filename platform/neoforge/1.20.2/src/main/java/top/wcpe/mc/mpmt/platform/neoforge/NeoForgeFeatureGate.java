package top.wcpe.mc.mpmt.platform.neoforge;

import java.util.EnumSet;
import java.util.Set;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;

/**
 * NeoForge 能力探测：NeoForge 无 Folia 区域调度；同进程存在 Bukkit 即融合服；客户端发行环境内置集成服。
 *
 * <p>镜像 {@code ForgeFeatureGate}，仅 FML 包名改为 NeoForge（{@code net.neoforged.*}）。环境探测在非 NeoForge
 * 运行环境（如纯 JVM 测试）下不可用，保守判否（try/catch 兜底）。
 */
public final class NeoForgeFeatureGate implements FeatureGate {

    private final Set<Capability> supported;

    public NeoForgeFeatureGate() {
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        // NeoForge 无 Folia（REGION_SCHEDULER 否）
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
            return net.neoforged.fml.loading.FMLEnvironment.dist
                    == net.neoforged.api.distmarker.Dist.CLIENT;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, NeoForgeFeatureGate.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
