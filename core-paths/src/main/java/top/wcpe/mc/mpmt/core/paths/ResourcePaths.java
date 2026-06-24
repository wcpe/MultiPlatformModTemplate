package top.wcpe.mc.mpmt.core.paths;

import java.nio.file.Path;
import java.util.Objects;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;

/**
 * 资源路径预设（L1，ADR-0010）：在平台经 {@link DataDirectoryPort} 提供的基目录下，
 * 预设标准子目录（config / data / resources），调用方<b>引用预设位置</b>而不自行拼接绝对路径。
 * 客户端与服务端共用同一份预设，确保两端位置一致。
 *
 * <p>相对名经规范化后必须仍落在对应预设目录内，越界（如 {@code ../x}）或绝对路径即拒绝，
 * 守「不自算路径 / 不逃逸基目录」约束（architecture-invariants）。
 */
public final class ResourcePaths {

    /** 预设配置目录名。 */
    private static final String CONFIG_DIR = "config";
    /** 预设数据目录名。 */
    private static final String DATA_DIR = "data";
    /** 预设资源目录名（客户端 / 服务端共享）。 */
    private static final String RESOURCES_DIR = "resources";

    private final DataDirectoryPort dataDirectory;

    public ResourcePaths(DataDirectoryPort dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "数据基目录端口不能为空");
    }

    /** 平台提供的基目录。 */
    public Path baseDirectory() {
        return base();
    }

    /** 预设配置目录：{@code <base>/config}。 */
    public Path configDirectory() {
        return base().resolve(CONFIG_DIR);
    }

    /** 预设数据目录：{@code <base>/data}。 */
    public Path dataDirectory() {
        return base().resolve(DATA_DIR);
    }

    /** 预设资源目录：{@code <base>/resources}。 */
    public Path resourcesDirectory() {
        return base().resolve(RESOURCES_DIR);
    }

    /** 配置目录下的文件，如 {@code configFile("app.yml")}。 */
    public Path configFile(String relativeName) {
        return resolveWithin(configDirectory(), relativeName);
    }

    /** 数据目录下的文件，如 {@code dataFile("players.json")}。 */
    public Path dataFile(String relativeName) {
        return resolveWithin(dataDirectory(), relativeName);
    }

    /** 资源目录下的文件，如 {@code resourceFile("lang.json")}。 */
    public Path resourceFile(String relativeName) {
        return resolveWithin(resourcesDirectory(), relativeName);
    }

    /** 取平台基目录；平台未提供（返回空）即失败快。 */
    private Path base() {
        Path base = dataDirectory.baseDirectory();
        if (base == null) {
            throw new IllegalStateException("平台提供的基目录为空");
        }
        return base;
    }

    /** 在预设目录下解析相对名；规范化后越界（含绝对路径 / {@code ../} 逃逸）即拒绝。 */
    private static Path resolveWithin(Path presetDir, String relativeName) {
        Objects.requireNonNull(relativeName, "相对名不能为空");
        if (relativeName.trim().isEmpty()) {
            throw new IllegalArgumentException("相对名不能为空白");
        }
        Path normalizedDir = presetDir.normalize();
        Path resolved = normalizedDir.resolve(relativeName).normalize();
        if (!resolved.startsWith(normalizedDir)) {
            throw new IllegalArgumentException("相对名越界预设目录：" + relativeName);
        }
        return resolved;
    }
}
