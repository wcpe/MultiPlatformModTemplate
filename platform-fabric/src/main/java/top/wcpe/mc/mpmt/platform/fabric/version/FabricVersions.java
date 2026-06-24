package top.wcpe.mc.mpmt.platform.fabric.version;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric 运行期版本探测（L4，ADR-0003）：经 fabric-loader 元数据探测当前 MC 版本并选中锚点。
 *
 * <p>服务端与客户端入口共用，避免重复探测逻辑；缺失锚点经 {@link SupportedVersion#match(String)} 失败快。
 */
public final class FabricVersions {

    private FabricVersions() {
        // 工具类不实例化
    }

    /** 探测当前 MC 版本并选中锚点；无 Fabric 运行时或非锚点版本即失败快。 */
    public static SupportedVersion detect() {
        String mcVersion =
                FabricLoader.getInstance()
                        .getModContainer("minecraft")
                        .map(container -> container.getMetadata().getVersion().getFriendlyString())
                        .orElseThrow(() -> new IllegalStateException("无法探测 Minecraft 版本"));
        return SupportedVersion.match(mcVersion);
    }
}
