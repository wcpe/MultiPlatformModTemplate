package top.wcpe.mc.mpmt.platform.fabric.version;

import net.minecraft.resources.ResourceLocation;
import top.wcpe.mc.mpmt.platform.fabric.version.v1_20.V1_20ClientNetwork;
import top.wcpe.mc.mpmt.platform.fabric.version.v1_20.V1_20ServerNetwork;

/**
 * Fabric 网络绑定装配点（L4，ADR-0003）：按选中的 {@link SupportedVersion} 构造对应 {@code vX_Y} 网络绑定。
 *
 * <p>这里的 {@code switch} 是版本适配的<b>唯一</b>装配点（非散落业务 if-else）——新增版本=加一个分支 + 一个
 * {@code vX_Y} 绑定，不改上层。通道命名空间 / 路径在此集中定义，避免魔法值。
 */
public final class FabricNetworkBindings {

    /** 跨端通道命名空间。 */
    private static final String CHANNEL_NAMESPACE = "mpmt";
    /** 跨端通道路径（合为 {@code mpmt:main}，network spec §3.2）。 */
    private static final String CHANNEL_PATH = "main";

    /** 产品跨端通道（{@code mpmt:main}）单一真源，供平台收发与验收场景共用。 */
    public static final ResourceLocation PRODUCT_CHANNEL = new ResourceLocation(CHANNEL_NAMESPACE, CHANNEL_PATH);

    private FabricNetworkBindings() {
        // 工具类不实例化
    }

    /** 构造选中版本的服务端网络绑定。 */
    public static FabricServerNetwork serverNetwork(SupportedVersion version) {
        switch (version) {
            case V1_20:
                return new V1_20ServerNetwork(CHANNEL_NAMESPACE, CHANNEL_PATH);
            default:
                throw new IllegalStateException("无服务端网络绑定实现：" + version);
        }
    }

    /** 构造选中版本的客户端网络绑定（仅客户端环境调用）。 */
    public static FabricClientNetwork clientNetwork(SupportedVersion version) {
        switch (version) {
            case V1_20:
                return new V1_20ClientNetwork(CHANNEL_NAMESPACE, CHANNEL_PATH);
            default:
                throw new IllegalStateException("无客户端网络绑定实现：" + version);
        }
    }
}
