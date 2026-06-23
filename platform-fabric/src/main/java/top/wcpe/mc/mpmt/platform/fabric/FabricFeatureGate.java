package top.wcpe.mc.mpmt.platform.fabric;

import java.util.EnumSet;
import java.util.Set;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;

/**
 * Fabric 能力探测：Fabric 无 Folia 区域调度、非融合服；集成服回环在客户端发行环境可用。
 *
 * <p>环境探测经 FabricLoader；在非 Fabric 运行环境（如纯 JVM 测试）下 FabricLoader 不可用，保守判否。
 */
public final class FabricFeatureGate implements FeatureGate {

    private final Set<Capability> supported;

    public FabricFeatureGate() {
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        // Fabric 无 Folia（REGION_SCHEDULER 否）、非融合服（HYBRID_FORGE_BUKKIT 否）
        if (integratedServerAvailable()) {
            set.add(Capability.INTEGRATED_SERVER);
        }
        this.supported = set;
    }

    @Override
    public boolean supports(Capability capability) {
        return supported.contains(capability);
    }

    /** 客户端发行环境内置集成服（单人世界）；服务端发行环境无。 */
    private static boolean integratedServerAvailable() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                    == net.fabricmc.api.EnvType.CLIENT;
        } catch (Throwable ignored) {
            // 非 Fabric 运行环境（纯 JVM 测试等）下保守判否
            return false;
        }
    }
}
