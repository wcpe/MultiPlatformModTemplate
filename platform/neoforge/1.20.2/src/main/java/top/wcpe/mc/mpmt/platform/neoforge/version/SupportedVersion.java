package top.wcpe.mc.mpmt.platform.neoforge.version;

/** NeoForge 支持的 Minecraft 锚点版本（L4）。 */
public enum SupportedVersion {

    /** 当前锚点 1.20.2。 */
    V1_20_2("1.20.2");

    private final String mcVersion;

    SupportedVersion(String mcVersion) {
        this.mcVersion = mcVersion;
    }

    /** 返回锚点对应的 Minecraft 版本号。 */
    public String mcVersion() {
        return mcVersion;
    }

    /**
     * 按平台报告的 Minecraft 版本选择锚点。
     *
     * @throws IllegalStateException 未知版本时明确失败，禁止静默退化
     */
    public static SupportedVersion match(String mcVersion) {
        if (mcVersion == null) {
            throw new IllegalStateException("不支持的 Minecraft 版本（缺少 NeoForge vX_Y 适配）：null");
        }
        for (SupportedVersion version : values()) {
            if (version.mcVersion.equals(mcVersion)) {
                return version;
            }
        }
        throw new IllegalStateException("不支持的 Minecraft 版本（缺少 NeoForge vX_Y 适配）：" + mcVersion);
    }
}
