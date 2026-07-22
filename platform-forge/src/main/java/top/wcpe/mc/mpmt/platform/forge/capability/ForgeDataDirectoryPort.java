package top.wcpe.mc.mpmt.platform.forge.capability;

import java.nio.file.Path;
import net.minecraftforge.fml.loading.FMLPaths;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;

/** Forge 数据基目录端口：使用 Forge 配置目录下的 mpmt 子目录。 */
public final class ForgeDataDirectoryPort implements DataDirectoryPort {

    @Override
    public Path baseDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve("mpmt");
    }
}
