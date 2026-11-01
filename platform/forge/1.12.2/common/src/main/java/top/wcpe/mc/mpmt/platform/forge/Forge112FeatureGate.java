package top.wcpe.mc.mpmt.platform.forge;

import java.util.EnumSet;
import java.util.Set;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;

/**
 * Forge 1.12.2 能力探测：Forge 无 Folia 区域调度；同进程存在 Bukkit 即融合服；客户端发行环境内置集成服。
 *
 * <p>融合服探测用「类路径上有没有 {@code org.bukkit.Bukkit}」：CatServer 把 Bukkit 与 Forge 放同一进程，
 * 纯 Forge 专用服没有该类。探测不可用时（如纯 JVM 测试）保守判否。
 */
public final class Forge112FeatureGate implements FeatureGate {

    private final Set<Capability> supported;

    /** 按当前运行环境构造。 */
    public Forge112FeatureGate() {
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        // 1.12.2 Forge 没有 Folia，REGION_SCHEDULER 恒为否
        if (classPresent("org.bukkit.Bukkit")) {
            set.add(Capability.HYBRID_FORGE_BUKKIT);
        }
        if (clientSide()) {
            set.add(Capability.INTEGRATED_SERVER);
        }
        this.supported = set;
    }

    @Override
    public boolean supports(Capability capability) {
        return supported.contains(capability);
    }

    /** 客户端发行环境内置集成服（单人世界）；专用服无。 */
    private static boolean clientSide() {
        try {
            return net.minecraftforge.fml.common.FMLCommonHandler.instance().getSide().isClient();
        } catch (Throwable ignored) {
            // 非 Forge 运行环境（纯 JVM 测试）下不可用，保守判否
            return false;
        }
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, Forge112FeatureGate.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
