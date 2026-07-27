package top.wcpe.mc.mpmt.platform.fabric.version;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric 运行期版本探测与构建目标校验（L4，ADR-0003）。
 *
 * <p>构建期经 {@link SelectedFabricVersionFactory} 只打入唯一 L4；运行期
 * {@link #selected()} 校验实际 MC 与适配器锚点精确匹配。
 */
public final class FabricVersions {

    private FabricVersions() {
        // 工具类不实例化
    }

    /** 探测当前 MC 版本并选中锚点枚举；非支持版本失败快。 */
    public static SupportedVersion detect() {
        return SupportedVersion.match(actualMinecraftVersion());
    }

    /**
     * 加载构建期唯一 L4 适配器并要求与实际 MC 版本精确匹配。
     *
     * @throws IllegalStateException 版本不符或无法探测
     */
    public static FabricVersionAdapter selected() {
        FabricVersionAdapter adapter = SelectedFabricVersionFactory.create();
        requireExactMatch(adapter.minecraftVersion(), actualMinecraftVersion());
        return adapter;
    }

    /** 经 fabric-loader 元数据读取 Minecraft 友好版本串。 */
    public static String actualMinecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElseThrow(() -> new IllegalStateException("无法探测 Minecraft 版本"));
    }

    /** 实际版本必须与构建目标精确匹配。 */
    public static void requireExactMatch(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Fabric Minecraft 版本不匹配：expected=" + expected + ", actual=" + actual);
        }
    }
}
