package top.wcpe.mc.mpmt.platform.forge.modern.capability;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;

/** Forge 文件持久化端口：每个命名空间保存为数据目录下的一份 properties 文件。 */
public final class ForgePersistencePort implements PersistencePort {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    private final DataDirectoryPort dataDirectory;

    public ForgePersistencePort(DataDirectoryPort dataDirectory) {
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
        }
    }

    private Path file(String namespace) {
        return dataDirectory.baseDirectory().resolve("data").resolve(namespace + ".properties");
    }
}
