package top.wcpe.mc.mpmt.platform.neoforge.acceptance;

import net.minecraft.resources.ResourceLocation;

/**
 * NeoForge 验收控制通道 id（realserver harness，ADR-0014）：独立于产品通道 {@code mpmt:main}。
 *
 * <p>值与各端验收伴侣的控制通道一致（{@code mpmt-test:acceptance}），方能让我方验收伴侣连入服务端时收发同一
 * 控制协议。本期 realserver 目标为 NeoForge↔NeoForge（伴侣为真 NeoForge 客户端），SimpleChannel 自带握手协商。
 */
public final class NeoForgeAcceptanceControlChannelId {

    /** 验收控制通道命名空间。 */
    public static final String NAMESPACE = "mpmt-test";
    /** 验收控制通道路径。 */
    public static final String PATH = "acceptance";
    /** 验收控制通道资源位置（namespace:path）。 */
    public static final ResourceLocation CHANNEL = new ResourceLocation(NAMESPACE, PATH);

    private NeoForgeAcceptanceControlChannelId() {
        // 常量类不实例化
    }
}
