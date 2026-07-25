package top.wcpe.mc.mpmt.platform.bukkit.version;

import java.util.Objects;
import org.bukkit.plugin.Plugin;
import top.wcpe.mc.mpmt.platform.bukkit.net.BukkitConnectionRegistry;
import top.wcpe.mc.mpmt.platform.bukkit.version.v1_20.V1_20BukkitServerNetwork;

/**
 * Bukkit 网络版本绑定的唯一装配点。
 *
 * <p>方案 C 阶段 2：L4 适配器由 ServiceLoader 提供通道/调度；网络实现仍复用 tip
 * 的插件消息适配（Messenger API 在 1.12～1.21 形态一致，差异仅通道名）。
 */
public final class BukkitNetworkBindings {

    private BukkitNetworkBindings() {
        // 工具类不实例化
    }

    /**
     * 由已加载的 L4 适配器构造服务端网络。
     *
     * <p>通道名取自适配器；实现类统一为 {@link V1_20BukkitServerNetwork}
     * （命名历史：基于 1.20 车道落地，API 兼容 1.12 插件消息）。
     */
    public static BukkitServerNetwork serverNetwork(
            BukkitVersionAdapter adapter, Plugin plugin, BukkitConnectionRegistry connections) {
        Objects.requireNonNull(adapter, "adapter 不能为空");
        Objects.requireNonNull(plugin, "plugin 不能为空");
        Objects.requireNonNull(connections, "connections 不能为空");
        return new V1_20BukkitServerNetwork(plugin, connections, adapter.channels().product());
    }

    /** 兼容旧调用：先经枚举再走 ServiceLoader 装载的适配器不在此路径。 */
    public static BukkitServerNetwork serverNetwork(
            SupportedVersion version, Plugin plugin, BukkitConnectionRegistry connections) {
        Objects.requireNonNull(version, "version 不能为空");
        return serverNetwork(adapterFor(version), plugin, connections);
    }

    /**
     * 按锚点返回适配器。
     *
     * <p>构建产物只含唯一 L4；此处用 ServiceLoader 取唯一实现并校验锚点一致。
     * 单测若无 services 登记，会失败快——应用 {@link #serverNetwork(BukkitVersionAdapter, ...)}。
     */
    public static BukkitVersionAdapter adapterFor(SupportedVersion version) {
        Objects.requireNonNull(version, "version 不能为空");
        BukkitVersionAdapter loaded =
                BukkitVersionAdapters.load(BukkitNetworkBindings.class.getClassLoader());
        if (loaded.version() != version) {
            throw new IllegalStateException(
                    "产物 L4 锚点与请求不符：requested="
                            + version
                            + ", packaged="
                            + loaded.version());
        }
        return loaded;
    }
}
