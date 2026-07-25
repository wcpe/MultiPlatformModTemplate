package top.wcpe.mc.mpmt.platform.neoforge.version;

import java.util.function.Supplier;
import net.neoforged.fml.loading.FMLLoader;

/** NeoForge 运行期版本探测。 */
public final class NeoForgeVersions {

    private NeoForgeVersions() {
        // 工具类不实例化
    }

    /** 从 NeoForge 实际运行时元数据读取 Minecraft 版本。 */
    public static String probeMcVersion() {
        return FMLLoader.versionInfo().mcVersion();
    }

    /** 探测并选中当前锚点。 */
    public static SupportedVersion detect() {
        return detect(NeoForgeVersions::probeMcVersion);
    }

    /** 使用注入探测源选中锚点（便于纯 JVM 测试）。 */
    public static SupportedVersion detect(Supplier<String> versionProbe) {
        return SupportedVersion.match(versionProbe.get());
    }
}
