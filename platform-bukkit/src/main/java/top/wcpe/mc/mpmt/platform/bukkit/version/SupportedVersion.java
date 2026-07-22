package top.wcpe.mc.mpmt.platform.bukkit.version;

/** Bukkit 家族支持的 Minecraft 锚点版本。 */
public enum SupportedVersion {

    /** Bukkit、Spigot、Paper 与 Folia 共用的 1.20.1 锚点。 */
    V1_20("1.20.1");

    private final String mcVersion;

    SupportedVersion(String mcVersion) {
        this.mcVersion = mcVersion;
    }

    /** 返回锚点对应的 Minecraft 版本号。 */
    public String mcVersion() {
        return mcVersion;
    }

    /** 按平台报告的 Minecraft 版本选择锚点。 */
    public static SupportedVersion match(String mcVersion) {
        for (SupportedVersion version : values()) {
            if (version.mcVersion.equals(mcVersion)) {
                return version;
            }
        }
        throw new IllegalStateException("不支持的 Minecraft 版本（缺少 Bukkit vX_Y 适配）：" + mcVersion);
    }
}
