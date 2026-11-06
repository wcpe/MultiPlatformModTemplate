package top.wcpe.mc.mpmt.paths;

import java.nio.file.Path;
import java.util.Objects;
import top.wcpe.mc.mpmt.kernel.port.DataDirectoryPort;

/**
 * 平台无关的路径预设（L1，ADR-0010 决策 2）：调用方拿预设位置，不自算路径。
 *
 * <p>基目录一律由平台的 {@link DataDirectoryPort} 提供；本类只在其下拼<b>相对</b>预设目录，
 * 不硬编码任何绝对路径（architecture-invariants §1）。客户端与服务端共用同一套预设，
 * 保证两端资源位置一致（ADR-0010 决策 3）。
 *
 * <p>典型用法：
 *
 * <pre>{@code
 * Paths paths = Paths.of(dataDirectory);
 * Path cfg = paths.configFile("server.yml");   // <baseDir>/config/server.yml
 * Path data = paths.dataFile("stats.properties"); // <baseDir>/data/stats.properties
 * }</pre>
 *
 * <p><b>只负责定位、不负责创建</b>：返回的路径不保证存在，要不要建目录由写方决定
 * （读路径不该有副作用）。需要时用 {@link #ensureParent(Path)}。
 */
public final class Paths {

    /** 配置目录名。与 {@code data/} 平级。 */
    public static final String CONFIG_DIRECTORY = "config";

    /** 持久化数据目录名。 */
    public static final String DATA_DIRECTORY = "data";

    /** 共享资源目录名（如贴图、语言文件等随包资源落地处）。 */
    public static final String RESOURCES_DIRECTORY = "resources";

    private final DataDirectoryPort dataDirectory;

    private Paths(DataDirectoryPort dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    /**
     * 以平台基目录端口构造路径预设。
     *
     * @param dataDirectory 平台基目录端口，不可为空
     * @return 路径预设
     */
    public static Paths of(DataDirectoryPort dataDirectory) {
        return new Paths(Objects.requireNonNull(dataDirectory, "基目录端口不能为空"));
    }

    /** {@return 平台基目录（绝对路径）} */
    public Path baseDirectory() {
        return dataDirectory.baseDirectory();
    }

    /** {@return 配置目录 {@code <baseDir>/config}} */
    public Path configDirectory() {
        return baseDirectory().resolve(CONFIG_DIRECTORY);
    }

    /**
     * {@return 配置目录下的文件}
     *
     * @param fileName 文件名（不含目录分隔），不可为空
     */
    public Path configFile(String fileName) {
        return resolveUnder(configDirectory(), fileName);
    }

    /** {@return 数据目录 {@code <baseDir>/data}} */
    public Path dataDirectory() {
        return baseDirectory().resolve(DATA_DIRECTORY);
    }

    /**
     * {@return 数据目录下的文件}
     *
     * @param fileName 文件名（不含目录分隔），不可为空
     */
    public Path dataFile(String fileName) {
        return resolveUnder(dataDirectory(), fileName);
    }

    /** {@return 共享资源目录 {@code <baseDir>/resources}} */
    public Path resourcesDirectory() {
        return baseDirectory().resolve(RESOURCES_DIRECTORY);
    }

    /**
     * {@return 共享资源目录下的文件}
     *
     * @param fileName 文件名（不含目录分隔），不可为空
     */
    public Path resourceFile(String fileName) {
        return resolveUnder(resourcesDirectory(), fileName);
    }

    /**
     * 把 {@code fileName} 拼到 {@code directory} 下，并拒绝包含目录分隔的名字。
     *
     * <p>拒绝的原因：允许分隔符会让调用方用 {@code "../../etc/passwd"} 这类名字逃出基目录，
     * 而本类的契约就是「只在基目录下定位」（ADR-0010）。需要子目录时显式用
     * {@code directory().resolve("sub").resolve(name)}，让越界在调用处可见。
     *
     * @param directory 目标目录
     * @param fileName 文件名
     * @return 拼好的路径
     * @throws IllegalArgumentException 名字为空或含目录分隔符
     */
    public static Path resolveUnder(Path directory, String fileName) {
        Objects.requireNonNull(directory, "目录不能为空");
        Objects.requireNonNull(fileName, "文件名不能为空");
        if (fileName.isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空串");
        }
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("文件名不得包含目录分隔符：" + fileName);
        }
        if (fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException("文件名不得为相对目录：" + fileName);
        }
        return directory.resolve(fileName);
    }

    /**
     * 确保 {@code file} 的父目录存在（写前调用）。
     *
     * <p>独立的静态方法而非隐含在 {@code *File} 里：读路径不该产生副作用。
     *
     * @param file 目标文件
     * @throws java.io.UncheckedIOException 无法创建目录
     */
    public static void ensureParent(Path file) {
        Objects.requireNonNull(file, "文件路径不能为空");
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(parent);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("无法创建目录：" + parent, e);
        }
    }
}
