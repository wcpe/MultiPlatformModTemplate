package top.wcpe.mc.mpmt.platform.sponge.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.world.server.ServerWorld;
import top.wcpe.mc.mpmt.core.domain.port.WorldPort;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/** Sponge 世界查询端口：按资源键字符串暴露当前已加载世界。 */
public final class SpongeWorldPort implements WorldPort {

    @Override
    public boolean isLoaded(String worldId) {
        return resolve(worldId).isPresent();
    }

    @Override
    public List<WorldRef> loadedWorlds() {
        List<WorldRef> worlds = new ArrayList<>();
        for (ServerWorld world : Sponge.server().worldManager().worlds()) {
            worlds.add(toRef(world));
        }
        return Collections.unmodifiableList(worlds);
    }

    @Override
    public Optional<WorldRef> resolve(String worldId) {
        for (ServerWorld world : Sponge.server().worldManager().worlds()) {
            WorldRef ref = toRef(world);
            if (ref.getId().equals(worldId)) {
                return Optional.of(ref);
            }
        }
        return Optional.empty();
    }

    private static WorldRef toRef(ServerWorld world) {
        return new WorldRef(world.key().formatted());
    }
}
