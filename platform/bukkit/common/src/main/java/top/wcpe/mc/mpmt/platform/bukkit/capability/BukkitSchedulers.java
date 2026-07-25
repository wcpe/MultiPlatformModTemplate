package top.wcpe.mc.mpmt.platform.bukkit.capability;

import java.util.Objects;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter;
import top.wcpe.mc.mpmt.platform.spi.Capability;
import top.wcpe.mc.mpmt.platform.spi.FeatureGate;

/**
 * Bukkit 家族 {@link SchedulerPort} 选用（L3）：只把能力位传给选中的 L4 适配器，
 * 具体传统 Bukkit / Folia 类型不会泄漏进公共 L3 的选用分支以外。
 *
 * <p>1.12 适配器忽略区域调度位；现代适配器按 capability 选 Folia 或主线程。
 */
public final class BukkitSchedulers {

    private BukkitSchedulers() {
        // 工具类不实例化
    }

    /** 按平台能力经 L4 适配器创建调度端口。 */
    public static SchedulerPort create(
            Plugin plugin, FeatureGate featureGate, BukkitVersionAdapter adapter) {
        Objects.requireNonNull(plugin, "plugin 不能为空");
        Objects.requireNonNull(featureGate, "featureGate 不能为空");
        Objects.requireNonNull(adapter, "版本适配器不能为空");
        boolean regionScheduler = featureGate.supports(Capability.REGION_SCHEDULER);
        return adapter.createScheduler(plugin, regionScheduler);
    }
}
