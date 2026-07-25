package top.wcpe.mc.mpmt.platform.bukkit.version.v1_21;

import top.wcpe.mc.mpmt.platform.bukkit.version.SupportedVersion;
import top.wcpe.mc.mpmt.platform.bukkit.version.modern.ModernBukkitVersionAdapter;

/** Paper 1.21.1 L4 适配器。 */
public final class V1_21BukkitVersionAdapter extends ModernBukkitVersionAdapter {

    @Override
    public SupportedVersion version() {
        return SupportedVersion.V1_21;
    }
}
