package top.wcpe.mc.mpmt.platform.sponge.capability;

import java.nio.file.Path;
import java.util.Objects;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;

/**
 * Sponge 数据基目录端口（L3，FR-30）：以插件专属配置目录（{@code config/mpmt/}，经 {@code @ConfigDir} 注入）
 * 为基目录，共享层（core-paths / 持久化）在其下拼相对预设位置（ADR-0010）。
 */
public final class SpongeDataDirectoryPort implements DataDirectoryPort {

    private final Path baseDirectory;

    public SpongeDataDirectoryPort(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory 不能为空");
    }

    @Override
    public Path baseDirectory() {
        return baseDirectory;
    }
}
