package top.wcpe.mc.mpmt.platform.forge.capability;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import top.wcpe.mc.mpmt.kernel.port.DataDirectoryPort;

/**
 * Forge 1.12.2 数据基目录端口：mod 配置目录下的 {@code mpmt} 子目录（ADR-0010）。
 *
 * <p>基目录由 FML 启动事件给出（{@code FMLPreInitializationEvent.getModConfigurationDirectory()}），
 * 故本类只接收一个目录、不自己去问 FML——这样纯 JVM 测试可以直接给临时目录。
 */
public final class Forge112DataDirectoryPort implements DataDirectoryPort {

    /** 基目录名：与其它车道保持同名，配置文件落在 {@code <配置目录>/mpmt/config/}。 */
    private static final String BASE_DIRECTORY_NAME = "mpmt";

    private final Path baseDirectory;

    /**
     * 构造。
     *
     * @param configDirectory FML 提供的配置目录，不可为空
     */
    public Forge112DataDirectoryPort(File configDirectory) {
        Objects.requireNonNull(configDirectory, "配置目录不能为空");
        this.baseDirectory = configDirectory.toPath().resolve(BASE_DIRECTORY_NAME);
    }

    @Override
    public Path baseDirectory() {
        return baseDirectory;
    }
}
