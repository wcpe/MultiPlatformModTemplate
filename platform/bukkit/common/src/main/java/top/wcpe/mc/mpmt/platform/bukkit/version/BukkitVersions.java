package top.wcpe.mc.mpmt.platform.bukkit.version;

import java.util.Objects;
import org.bukkit.plugin.Plugin;

/**
 * Bukkit 运行期 Minecraft 版本探测与精确匹配。
 *
 * <p>主路径：构建期选定唯一 L4 后，用 {@link #requireExactMatch} 校验实际服版本。
 * 兼容探测：{@link #detect} 供单测注入或诊断（优先 {@code getMinecraftVersion}，回退 Bukkit 版本串）。
 */
public final class BukkitVersions {

    private BukkitVersions() {
        // 工具类不实例化
    }

    /** 实际 Bukkit/MC 版本必须与构建目标精确匹配。 */
    public static void requireExactMatch(String expected, String actual) {
        SupportedVersion.match(expected);
        String parsed = parse(actual);
        if (!expected.equals(parsed)) {
            throw new IllegalStateException(
                    "Bukkit Minecraft 版本不匹配：expected=" + expected + ", actual=" + actual);
        }
    }

    /**
     * 从运行中的服务端探测锚点版本。
     *
     * <p>优先 Paper/现代 API 的 {@code getMinecraftVersion()}；1.12 等仅有 Bukkit 版本串时回退解析。
     */
    public static SupportedVersion detect(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin 不能为空");
        String raw = readServerVersion(plugin);
        String parsed = parse(raw);
        if (parsed == null) {
            throw new IllegalStateException("无法解析 Minecraft 版本：" + raw);
        }
        return SupportedVersion.match(parsed);
    }

    private static String readServerVersion(Plugin plugin) {
        try {
            Object version =
                    plugin.getServer().getClass().getMethod("getMinecraftVersion").invoke(plugin.getServer());
            if (version != null) {
                return version.toString();
            }
        } catch (ReflectiveOperationException ignored) {
            // 1.12 等无此方法，回退 Bukkit 版本串
        }
        return plugin.getServer().getBukkitVersion();
    }

    private static String parse(String actual) {
        if (actual == null) {
            return null;
        }
        String value = actual.trim();
        int end = versionPrefixEnd(value);
        return end == 0 ? null : value.substring(0, end);
    }

    private static int versionPrefixEnd(String value) {
        int dots = 0;
        boolean digitInPart = false;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current >= '0' && current <= '9') {
                digitInPart = true;
                index++;
            } else if (current == '.' && digitInPart && dots < 2) {
                dots++;
                digitInPart = false;
                index++;
            } else {
                break;
            }
        }
        if (!digitInPart || dots == 0 || (index < value.length() && value.charAt(index) == '.')) {
            return 0;
        }
        return index;
    }
}
