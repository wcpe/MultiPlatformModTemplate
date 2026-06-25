package top.wcpe.mc.mpmt.core.config;

import java.util.Locale;

/**
 * 支持的配置格式（ADR-0010）：按文件扩展名判别。以枚举多态承载「扩展名→格式」映射，
 * 消灭散落的格式 if-else（反模式禁令 §6）。
 */
public enum ConfigFormat {

    /** YAML：扩展名 {@code .yml} / {@code .yaml}。 */
    YAML("yml", "yaml"),
    /** JSON：扩展名 {@code .json}。 */
    JSON("json");

    private final String[] extensions;

    ConfigFormat(String... extensions) {
        this.extensions = extensions;
    }

    /**
     * 按文件名扩展名（忽略大小写）判别配置格式。
     *
     * @throws IllegalArgumentException 文件名为空 / 无扩展名 / 扩展名不支持
     */
    public static ConfigFormat fromFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String ext = extensionOf(fileName);
        if (ext == null) {
            throw new IllegalArgumentException("文件名缺少扩展名，无法判别配置格式：" + fileName);
        }
        for (ConfigFormat format : values()) {
            for (String candidate : format.extensions) {
                if (candidate.equals(ext)) {
                    return format;
                }
            }
        }
        throw new IllegalArgumentException("不支持的配置扩展名：" + ext);
    }

    /** 取文件名的小写扩展名；仅看最后一段路径，避免目录名中的 '.' 干扰；无扩展名返回 null。 */
    private static String extensionOf(String fileName) {
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
