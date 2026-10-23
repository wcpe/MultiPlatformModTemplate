package top.wcpe.mc.mpmt.platform.fabric.version;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Fabric 网络绑定装配点（L4，ADR-0003）。
 *
 * <p>方案 C 阶段 2：构建期只编入唯一 L4（{@link SelectedFabricVersionFactory}），
 * 运行期经 {@link FabricVersions#selected()} 取适配器。保留 {@link #PRODUCT_CHANNEL}
 * 供 gametest / 验收场景与 tip 语义一致。
 */
public final class FabricNetworkBindings {

    /** 产品跨端通道（{@code mpmt:main}）单一真源。 */
    public static final ResourceLocation PRODUCT_CHANNEL =
            FabricResourceLocations.of(
                    FabricChannels.PRODUCT.namespace(), FabricChannels.PRODUCT.path());

    private FabricNetworkBindings() {
        // 工具类不实例化
    }

    /** 当前构建目标的唯一 L4 适配器（含运行期精确版本校验）。 */
    public static FabricVersionAdapter selectedAdapter() {
        return FabricVersions.selected();
    }

    /** 产品通道服务端网络。 */
    public static FabricServerNetwork productServer(FabricVersionAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter 不能为空");
        return adapter.serverNetwork(FabricChannels.PRODUCT);
    }

    /** 产品通道客户端网络。 */
    public static FabricClientNetwork productClient(FabricVersionAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter 不能为空");
        return adapter.clientNetwork(FabricChannels.PRODUCT);
    }

    /**
     * 按锚点枚举构造服务端网络（兼容 tip 装配入口）。
     *
     * <p>实际实现始终来自构建期唯一 L4；若枚举与产物锚点不符则失败快。
     */
    public static FabricServerNetwork serverNetwork(SupportedVersion version) {
        Objects.requireNonNull(version, "version 不能为空");
        FabricVersionAdapter adapter = selectedAdapter();
        if (!version.mcVersion().equals(adapter.minecraftVersion())) {
            throw new IllegalStateException(
                    "产物 L4 锚点与请求不符：requested="
                            + version.mcVersion()
                            + ", packaged="
                            + adapter.minecraftVersion());
        }
        return productServer(adapter);
    }

    /** 按锚点枚举构造客户端网络（兼容 tip 客户端入口）。 */
    public static FabricClientNetwork clientNetwork(SupportedVersion version) {
        Objects.requireNonNull(version, "version 不能为空");
        FabricVersionAdapter adapter = selectedAdapter();
        if (!version.mcVersion().equals(adapter.minecraftVersion())) {
            throw new IllegalStateException(
                    "产物 L4 锚点与请求不符：requested="
                            + version.mcVersion()
                            + ", packaged="
                            + adapter.minecraftVersion());
        }
        return productClient(adapter);
    }
}
