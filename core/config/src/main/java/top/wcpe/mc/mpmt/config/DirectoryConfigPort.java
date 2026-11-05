package top.wcpe.mc.mpmt.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import top.wcpe.mc.mpmt.kernel.port.ConfigPort;
import top.wcpe.mc.mpmt.kernel.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.paths.Paths;

/**
 * {@link ConfigPort} 的文件实现（L1）：把文件名解析到平台配置目录 {@code <baseDir>/config/} 下。
 *
 * <p>配置目录的位置不在本类自己拼——经 {@link Paths} 的预设取（ADR-0010 决策 2），
 * 保证与持久化的 {@code data/}、共享的 {@code resources/} 同一套预设，不会各自算出不同结果。
 *
 * <p>snakeyaml 只活在这一层：域拿到的是类型化模型，看不到也背不动 snakeyaml（ADR-0030 决策 5）。
 *
 * <p>语义与 {@code ConfigService} 一致：文件不存在返回空；存在但读/解析失败抛
 * {@link ConfigLoadException}，不静默回落。
 */
public final class DirectoryConfigPort implements ConfigPort {

    /**
     * 缺省配置模板的类路径前缀。
     *
     * <p>约定：每个自带配置的域在 <b>自己的</b> jar 里放 {@code mpmt-defaults/<文件名>}，
     * 由本类在首次启动时拷到 {@code <baseDir>/config/<文件名>}。
     * 为什么在域自己的 jar 里而不在本模块：模板内容属于域（字段随域演进），
     * 集中放在本模块会让域改一个默认值就要改基础设施模块，违背 ADR-0030。
     */
    public static final String DEFAULT_TEMPLATE_PREFIX = "mpmt-defaults/";

    private final Paths paths;
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
        this.paths = Paths.of(Objects.requireNonNull(dataDirectory, "基目录端口不能为空"));
        this.configService = Objects.requireNonNull(configService, "配置服务不能为空");
    }

    @Override
    public <T> Optional<T> read(String fileName, Class<T> type) {
        Objects.requireNonNull(fileName, "配置文件名不能为空");
        Objects.requireNonNull(type, "目标类型不能为空");
        Path file = paths.configFile(fileName);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(configService.load(file, type));
    }

    @Override
    public boolean writeDefault(String fileName) {
        Objects.requireNonNull(fileName, "配置文件名不能为空");
        Path target = paths.configFile(fileName);

        // 幂等：已存在就一字不改。用户可能已经按自己的环境改过，
        // 用默认值把它们盖掉是最不可原谅的实现方式。
        if (Files.exists(target)) {
            return false;
        }

        byte[] template = readTemplate(fileName);

        try {
            Paths.ensureParent(target);
            Files.write(target, template);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("写缺省配置失败：" + target, e);
        }
    }

    /**
     * 从类路径读缺省模板 {@code mpmt-defaults/<fileName>}。
     *
     * <p>找不到就失败而不是写个空文件：没有模板却声称"已写入缺省配置"，
     * 会让用户拿到一个看起来正常、实际什么都没有的配置，比缺文件更难排查。
     *
     * @param fileName 配置文件名
     * @return 模板字节（UTF-8 文本）
     */
    private static byte[] readTemplate(String fileName) {
        String resource = DEFAULT_TEMPLATE_PREFIX + fileName;
        ClassLoader loader = DirectoryConfigPort.class.getClassLoader();

        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UncheckedIOException(
                        new IOException("缺少缺省配置模板：" + resource + "（应在声明该配置的域 jar 内）"));
            }
            return readFully(in);
        } catch (IOException e) {
            throw new UncheckedIOException("读缺省配置模板失败：" + resource, e);
        }
    }

    /**
     * 读尽一个流的全部字节。
     *
     * <p>不用 {@code InputStream.readAllBytes()}：那是 Java 9+ API，而本模块按 ADR-0004
     * 严格编译为 Java 8 字节码（否则 1.12 Forge 加载不了）。同理不用 {@code var}（Java 10+）。
     *
     * @param in 输入流
     * @return 全部字节
     * @throws IOException 读失败
     */
    private static byte[] readFully(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * 把缺省模板的文本读出来（供测试与工具校验模板内容，不写盘）。
     *
     * @param fileName 配置文件名
     * @return 模板文本
     */
    public static String readDefaultTemplate(String fileName) {
        Objects.requireNonNull(fileName, "配置文件名不能为空");
        return new String(readTemplate(fileName), StandardCharsets.UTF_8);
    }
}
