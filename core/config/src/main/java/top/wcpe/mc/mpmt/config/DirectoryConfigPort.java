package top.wcpe.mc.mpmt.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import top.wcpe.mc.mpmt.kernel.port.ConfigPort;
import top.wcpe.mc.mpmt.kernel.port.DataDirectoryPort;

/**
 * {@link ConfigPort} 的文件实现（L1）：把文件名解析到平台配置目录 {@code <baseDir>/config/} 下。
 *
 * <p>snakeyaml 只活在这一层：域拿到的是类型化模型，看不到也背不动 snakeyaml（ADR-0030 决策 5）。
 *
 * <p>语义与 {@code ConfigService} 一致：文件不存在返回空；存在但读/解析失败抛
 * {@link ConfigLoadException}，不静默回落。
 */
public final class DirectoryConfigPort implements ConfigPort {

    /** 配置目录名，与 {@code PersistencePort} 的 {@code data/} 平级。 */
    private static final String CONFIG_DIRECTORY = "config";

    private final DataDirectoryPort dataDirectory;
    private final ConfigService configService;

    /**
     * 以平台基目录构造（内部自建 {@link ConfigService}）。
     *
     * @param dataDirectory 平台基目录端口，不可为空
     */
    public DirectoryConfigPort(DataDirectoryPort dataDirectory) {
        this(dataDirectory, new ConfigService());
    }

    /**
     * 以显式配置服务构造（供替换 loader 或测试）。
     *
     * @param dataDirectory 平台基目录端口，不可为空
     * @param configService 配置加载门面，不可为空
     */
    public DirectoryConfigPort(DataDirectoryPort dataDirectory, ConfigService configService) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "基目录端口不能为空");
        this.configService = Objects.requireNonNull(configService, "配置服务不能为空");
    }

    @Override
    public <T> Optional<T> read(String fileName, Class<T> type) {
        Objects.requireNonNull(fileName, "配置文件名不能为空");
        Objects.requireNonNull(type, "目标类型不能为空");
        Path file = dataDirectory.baseDirectory().resolve(CONFIG_DIRECTORY).resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(configService.load(file, type));
    }
}
