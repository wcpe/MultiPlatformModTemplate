package top.wcpe.mc.mpmt.platform.bukkit.version;

/** Bukkit 家族支持的 Minecraft 锚点版本（构建期与运行期精确匹配）。 */
public enum SupportedVersion {

    /** Bukkit/Spigot/CatServer 1.12.2。 */
    V1_12("1.12.2"),
    /** Paper/Spigot/Folia 1.20.1。 */
    V1_20("1.20.1"),
    /** Paper/Folia 1.21.1。 */
    V1_21("1.21.1"),
    /** Paper 26.2（新版号方案，无 1. 前缀）。 */
    V26_2("26.2");

    private final String mcVersion;

    SupportedVersion(String mcVersion) {
        this.mcVersion = mcVersion;
    }

    /** 返回锚点对应的精确 Minecraft 版本号。 */
    public String mcVersion() {
        return mcVersion;
    }

    /** 按平台报告的 Minecraft 版本选择锚点；未知版本失败快。 */
    public static SupportedVersion match(String mcVersion) {
        for (SupportedVersion version : values()) {
            if (version.mcVersion.equals(mcVersion)) {
                return version;
            }
        }
        throw new IllegalStateException("不支持的 Minecraft 版本（缺少 Bukkit vX_Y 适配）：" + mcVersion);
    }
}
