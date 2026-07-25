package top.wcpe.mc.mpmt.platform.fabric.capability;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;

/**
 * Fabric 数据基目录端口（L3，FR-30）：以 fabric-loader 配置目录下的 {@code mpmt} 子目录为基目录，
 * 共享层（core-paths / 持久化）在其下拼相对预设位置（ADR-0010）。
 */
public final class FabricDataDirectoryPort implements DataDirectoryPort {

    @Override
    public Path baseDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("mpmt");
    }
}
