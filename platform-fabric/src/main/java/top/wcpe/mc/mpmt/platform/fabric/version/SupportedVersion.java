package top.wcpe.mc.mpmt.platform.fabric.version;

/**
 * Fabric 平台支持的 Minecraft 锚点版本（L4 版本适配，ADR-0003）。
 *
 * <p>运行期探测当前 MC 版本字符串 → {@link #match(String)} 选中对应锚点 → 装配其 {@code vX_Y} 实现
 * （见 {@link FabricNetworkBindings}）。缺失实现时<b>明确失败</b>、不静默退化（testing-and-quality §2）。
 * 本枚举为纯逻辑、不引用任何 MC 类型，可纯 JVM 单测穷举。新增版本 = 加一个枚举项 + 对应 {@code vX_Y} 绑定。
 */
public enum SupportedVersion {

    /** 锚点 1.20.1。 */
    V1_20("1.20.1");

    private final String mcVersion;

    SupportedVersion(String mcVersion) {
        this.mcVersion = mcVersion;
    }

    /** 该锚点的 MC 版本号。 */
    public String mcVersion() {
        return mcVersion;
    }

    /**
     * 按 MC 版本号选中锚点。
     *
     * @throws IllegalStateException 无匹配锚点（缺少 vX_Y 适配），明确失败而非静默退化
     */
    public static SupportedVersion match(String mcVersion) {
        for (SupportedVersion version : values()) {
            if (version.mcVersion.equals(mcVersion)) {
                return version;
            }
        }
        throw new IllegalStateException("不支持的 Minecraft 版本（缺少对应 vX_Y 适配）：" + mcVersion);
    }
}
