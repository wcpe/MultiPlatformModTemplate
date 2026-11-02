package top.wcpe.mc.mpmt.platform.forge.capability;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import top.wcpe.mc.mpmt.kernel.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.kernel.port.PersistencePort;

/**
 * Forge 1.12.2 文件持久化端口：每个命名空间保存为数据目录下的一份 properties 文件。
 *
 * <p>语义与其它车道一致：读失败与写失败都抛 {@link UncheckedIOException}，不静默吞（吞异常反模式）。
 * 1.12.2 只有 log4j2，故日志门面用 {@code org.apache.logging.log4j}（不用 slf4j）。
 */
public final class Forge112PersistencePort implements PersistencePort {

    private static final Logger LOGGER = LogManager.getLogger("mpmt");

    private final DataDirectoryPort dataDirectory;

    /**
     * 构造。
     *
     * @param dataDirectory 平台基目录端口，不可为空
     */
    public Forge112PersistencePort(DataDirectoryPort dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory 不能为空");
    }

    @Override
    public synchronized Optional<String> read(String namespace, String key) {
        return Optional.ofNullable(load(namespace).getProperty(key));
    }

    @Override
    public synchronized void write(String namespace, String key, String value) {
        Properties properties = load(namespace);
        properties.setProperty(key, value);
        store(namespace, properties);
    }

    private Properties load(String namespace) {
        Properties properties = new Properties();
        Path file = file(namespace);
        if (Files.exists(file)) {
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                LOGGER.warn("读持久化文件失败：{}", file, e);
                throw new UncheckedIOException("读持久化文件失败：" + file, e);
            }
        }
        return properties;
    }

    private void store(String namespace, Properties properties) {
        Path file = file(namespace);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                properties.store(writer, "mpmt persistence");
            }
        } catch (IOException e) {
            LOGGER.warn("写持久化文件失败：{}", file, e);
            throw new UncheckedIOException("写持久化文件失败：" + file, e);
        }
    }

    private Path file(String namespace) {
        return dataDirectory.baseDirectory().resolve("data").resolve(namespace + ".properties");
    }
}
