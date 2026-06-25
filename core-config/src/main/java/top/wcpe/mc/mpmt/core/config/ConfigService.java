package top.wcpe.mc.mpmt.core.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 配置加载门面（L1，ADR-0010）：按文件扩展名（或显式格式）选 loader，以 UTF-8 读文件并委派，
 * 文件 IO / 解析 / 格式不可判别等失败统一抛 {@link ConfigLoadException}。平台无关、客户端服务端共用。
 *
 * <p>本类只负责「文件 → 类型化模型」的格式判别与读入；基目录解析由 {@code core-paths} +
 * {@code DataDirectoryPort} 负责，调用方组合（如 {@code service.load(paths.configFile("app.yml"), T.class)}）。
 */
public final class ConfigService {

    private final Map<ConfigFormat, ConfigLoader> loaders;

    public ConfigService() {
        Map<ConfigFormat, ConfigLoader> map = new EnumMap<>(ConfigFormat.class);
        map.put(ConfigFormat.YAML, new YamlConfigLoader());
        map.put(ConfigFormat.JSON, new JsonConfigLoader());
        this.loaders = map;
    }

    /** 按文件扩展名判别格式后加载。 */
    public <T> T load(Path file, Class<T> type) {
        Objects.requireNonNull(file, "file 不能为空");
        ConfigFormat format;
        try {
            format = ConfigFormat.fromFileName(file.getFileName().toString());
        } catch (IllegalArgumentException e) {
            throw new ConfigLoadException("无法判别配置格式：" + file, e);
        }
        return load(file, format, type);
    }

    /** 以显式格式加载，覆盖扩展名判别（用于扩展名非标准但格式已知的场景）。 */
    public <T> T load(Path file, ConfigFormat format, Class<T> type) {
        Objects.requireNonNull(file, "file 不能为空");
        Objects.requireNonNull(format, "format 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        ConfigLoader loader = loaders.get(format);
        if (loader == null) {
            throw new ConfigLoadException("无对应加载器的配置格式：" + format);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return loader.load(reader, type);
        } catch (IOException e) {
            // 文件缺失（NoSuchFileException）/ 读失败等统一以业务异常对外
            throw new ConfigLoadException("配置文件读取失败：" + file, e);
        }
    }
}
