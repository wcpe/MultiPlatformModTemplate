package top.wcpe.mc.mpmt.platform.sponge.acceptance;

import org.spongepowered.api.ResourceKey;

/**
 * Sponge 验收控制通道 id（realserver harness，ADR-0014）：独立于产品通道 {@code mpmt:main}。
 *
 * <p>值须与 Fabric 客户端伴侣的控制通道一致（{@code mpmt-test:acceptance}），方能让我方 Fabric 验收伴侣
 * 连入 SpongeVanilla 服务端时收发同一控制协议（异构互通，同 Bukkit 模式）。
 */
public final class SpongeAcceptanceControlChannelId {

    /** 验收控制通道 namespace。 */
    public static final String NAMESPACE = "mpmt-test";
    /** 验收控制通道 path。 */
    public static final String PATH = "acceptance";
    /** 验收控制通道键（{@code mpmt-test:acceptance}）。 */
    public static final ResourceKey CHANNEL = ResourceKey.of(NAMESPACE, PATH);

    private SpongeAcceptanceControlChannelId() {
        // 常量类不实例化
    }
}
