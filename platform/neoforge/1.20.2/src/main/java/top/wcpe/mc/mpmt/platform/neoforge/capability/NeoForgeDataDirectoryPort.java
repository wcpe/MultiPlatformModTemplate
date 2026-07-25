package top.wcpe.mc.mpmt.platform.neoforge.capability;

import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;

/**
 * NeoForge 数据基目录端口（L3，FR-30）：以 NeoForge 配置目录下的 {@code mpmt} 子目录为基目录，
 * 共享层（core-paths / 持久化）在其下拼相对预设位置（ADR-0010）。
 *
 * <p>与 Fabric 取 fabric-loader 配置目录同义，NeoForge 经 {@link FMLPaths#CONFIGDIR} 提供（{@code config/mpmt}）。
 */
public final class NeoForgeDataDirectoryPort implements DataDirectoryPort {

    @Override
    public Path baseDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve("mpmt");
    }
}
