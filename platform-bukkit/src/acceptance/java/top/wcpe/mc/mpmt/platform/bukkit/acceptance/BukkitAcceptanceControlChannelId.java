package top.wcpe.mc.mpmt.platform.bukkit.acceptance;

/**
 * Bukkit 验收控制通道 id（realserver harness，ADR-0014）：独立于产品通道 {@code mpmt:main}。
 *
 * <p>值须与 Fabric 客户端伴侣的控制通道一致（{@code mpmt-test:acceptance}），方能让我方 Fabric 验收伴侣
 * 连入 Bukkit/Paper 服务端时收发同一控制协议（异构互通，FR-11②）。
 */
public final class BukkitAcceptanceControlChannelId {

    /** 验收控制通道（插件消息通道名，namespace:path）。 */
    public static final String CHANNEL = "mpmt-test:acceptance";

    private BukkitAcceptanceControlChannelId() {
        // 常量类不实例化
    }
}
