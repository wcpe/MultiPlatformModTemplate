package top.wcpe.mc.mpmt.platform.sponge;

import java.util.EnumSet;
import java.util.Set;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;

/**
 * Sponge 能力探测：SpongeVanilla 专用服无 Folia 区域调度、为独立平台（不与我方 Bukkit/Forge 同进程融合）、
 * 纯服务端无内置集成服，故当前能力位全否。结构与其它平台 FeatureGate 一致，便于未来按需扩展。
 */
public final class SpongeFeatureGate implements FeatureGate {

    private final Set<Capability> supported = EnumSet.noneOf(Capability.class);

    @Override
    public boolean supports(Capability capability) {
        return supported.contains(capability);
    }
}
