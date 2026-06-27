package top.wcpe.mc.mpmt.platform.sponge.capability;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;

/**
 * Sponge 文件持久化端口（L3，FR-26）：每个命名空间一份 properties 文件，存于
 * {@code <baseDir>/data/<namespace>.properties}（基目录经 {@link DataDirectoryPort} 由平台提供）。
 *
 * <p>读 / 写经 {@code synchronized} 收敛并发（持久化为偶发操作、粗锁足够）；UTF-8 读写避免默认编码丢中文。
 * 与 Fabric / Bukkit / NeoForge 实现镜像，仅基目录来源与日志栈不同（Sponge 用 log4j2）。
 */
public final class SpongePersistencePort implements PersistencePort {

    private static final Logger LOGGER = LogManager.getLogger("mpmt");

    private final DataDirectoryPort dataDirectory;

    public SpongePersistencePort(DataDirectoryPort dataDirectory) {
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
