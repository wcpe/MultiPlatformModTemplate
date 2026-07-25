package top.wcpe.mc.mpmt.platform.forge.acceptance;

import net.minecraft.resources.ResourceLocation;

/**
 * Forge 验收控制通道 id（realserver harness，ADR-0014）：独立于产品通道 {@code mpmt:main}。
 *
 * <p>值须与 Fabric 客户端伴侣的控制通道一致（{@code mpmt-test:acceptance}），方能让我方 Fabric 验收伴侣
 * 连入 Forge 服务端时收发同一控制协议（异构互通，FR-11②）。
 */
public final class ForgeAcceptanceControlChannelId {

    /** 验收控制通道命名空间。 */
    public static final String NAMESPACE = "mpmt-test";
    /** 验收控制通道路径。 */
    public static final String PATH = "acceptance";
    /** 验收控制通道资源位置（namespace:path）。 */
    public static final ResourceLocation CHANNEL = new ResourceLocation(NAMESPACE, PATH);

    private ForgeAcceptanceControlChannelId() {
        // 常量类不实例化
    }
}
