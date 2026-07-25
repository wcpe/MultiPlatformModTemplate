package top.wcpe.mc.mpmt.platform.sponge.version;

import org.spongepowered.api.Sponge;

/** Sponge 运行期版本探测。 */
public final class SpongeVersions {

    private SpongeVersions() {
        // 工具类不实例化
    }

    /** 从实际 Sponge 平台 API 读取 Minecraft 版本并选择锚点。 */
    public static SupportedVersion detect() {
        return SupportedVersion.match(Sponge.platform().minecraftVersion().name());
    }
}
