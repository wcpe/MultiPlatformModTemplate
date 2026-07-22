package top.wcpe.mc.mpmt.platform.bukkit.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.World;
import top.wcpe.mc.mpmt.core.domain.port.WorldPort;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/** Bukkit 世界查询端口：只暴露当前已加载世界的字符串标识。 */
public final class BukkitWorldPort implements WorldPort {

    @Override
    public boolean isLoaded(String worldId) {
        return Bukkit.getWorld(worldId) != null;
    }

    @Override
    public List<WorldRef> loadedWorlds() {
        List<WorldRef> worlds = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            worlds.add(toRef(world));
        }
        return Collections.unmodifiableList(worlds);
    }

    @Override
    public Optional<WorldRef> resolve(String worldId) {
        World world = Bukkit.getWorld(worldId);
        return world == null ? Optional.empty() : Optional.of(toRef(world));
    }

    private static WorldRef toRef(World world) {
        return new WorldRef(world.getName());
    }
}
