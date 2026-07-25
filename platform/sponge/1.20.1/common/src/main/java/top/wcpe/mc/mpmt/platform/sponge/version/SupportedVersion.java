package top.wcpe.mc.mpmt.platform.sponge.version;

/** Sponge 平台支持的 Minecraft 锚点版本。 */
public enum SupportedVersion {

    /** SpongeVanilla RC1365 对应的 Minecraft 1.20.1 锚点。 */
    V1_20("1.20.1");

    private final String mcVersion;

    SupportedVersion(String mcVersion) {
        this.mcVersion = mcVersion;
    }

    /** 返回锚点对应的 Minecraft 版本号。 */
    public String mcVersion() {
        return mcVersion;
    }

    /** 按 Sponge 报告的 Minecraft 版本选择锚点。 */
    public static SupportedVersion match(String mcVersion) {
        for (SupportedVersion version : values()) {
            if (version.mcVersion.equals(mcVersion)) {
                return version;
            }
        }
        throw new IllegalStateException("不支持的 Minecraft 版本（缺少 Sponge vX_Y 适配）：" + mcVersion);
    }
}
