package top.wcpe.mc.mpmt.platform.bukkit.capability;

import java.util.Objects;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;

/**
 * Bukkit 家族 {@link SchedulerPort} 选用（L3，FR-13 / ADR-0013）：按 {@link FeatureGate} 探测的能力位选实现——
 * 探测到 {@link Capability#REGION_SCHEDULER}（Folia）用 {@link FoliaSchedulerPort} 按归属落区域 / 实体线程；
 * 否则用 {@link BukkitSchedulerPort} 退化为服务端主线程。
 *
 * <p>这是平台内"特判收敛"的唯一处（ADR-0003 / ADR-0013）：不在端口实现里散落 Folia if-else，而是一次性按能力位选实现。
 * 选用逻辑可纯 JVM 单测（FoliaSchedulerPort 构造期不触 Folia API），真实区域调度行为属 Folia 实机维度。
 */
public final class BukkitSchedulers {

    private BukkitSchedulers() {
        // 工具类不实例化
    }

    /** 按平台能力选 SchedulerPort：Folia→区域调度，否则→主线程调度。 */
    public static SchedulerPort create(Plugin plugin, FeatureGate featureGate) {
        Objects.requireNonNull(plugin, "plugin 不能为空");
        Objects.requireNonNull(featureGate, "featureGate 不能为空");
        if (featureGate.supports(Capability.REGION_SCHEDULER)) {
            return new FoliaSchedulerPort(plugin);
        }
        return new BukkitSchedulerPort(plugin);
    }
}
