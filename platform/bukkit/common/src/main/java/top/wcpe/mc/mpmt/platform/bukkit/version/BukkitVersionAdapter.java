package top.wcpe.mc.mpmt.platform.bukkit.version;

import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;

/**
 * Bukkit L4 版本适配接口：收敛通道与调度 API 差异。
 *
 * <p>网络装配仍在 L3：由 {@code BukkitNetworkBindings} 读取 {@link #channels()} 构造
 * 插件消息网络；本接口不依赖连接登记表，避免 version-api 反向依赖 main。
 */
public interface BukkitVersionAdapter {

    /** 该实现对应的锚点枚举。 */
    SupportedVersion version();

    /** 该版本的精确 Minecraft 版本号（与 {@link SupportedVersion#mcVersion()} 一致）。 */
    default String minecraftVersion() {
        return version().mcVersion();
    }

    /** 该版本的产品通道。 */
    BukkitChannels channels();

    /** 按运行环境能力创建调度端口。 */
    SchedulerPort createScheduler(Plugin plugin, boolean regionScheduler);

    /** 在服务端全局调度语义下执行任务。 */
    void executeGlobal(Plugin plugin, Runnable task);
}
