package top.wcpe.mc.mpmt.platform.fabric.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import top.wcpe.mc.mpmt.core.domain.port.WorldPort;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/** Fabric 世界查询端口：按维度资源标识暴露当前已加载世界。 */
public final class FabricWorldPort implements WorldPort {

    private final MinecraftServer server;

    public FabricWorldPort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    @Override
    public boolean isLoaded(String worldId) {
        return resolve(worldId).isPresent();
    }

    @Override
    public List<WorldRef> loadedWorlds() {
        List<WorldRef> worlds = new ArrayList<>();
        for (ServerLevel world : server.getAllLevels()) {
            worlds.add(toRef(world));
        }
        return Collections.unmodifiableList(worlds);
    }

    @Override
    public Optional<WorldRef> resolve(String worldId) {
        for (ServerLevel world : server.getAllLevels()) {
            WorldRef ref = toRef(world);
            if (ref.getId().equals(worldId)) {
                return Optional.of(ref);
            }
        }
        return Optional.empty();
    }

    private static WorldRef toRef(ServerLevel world) {
        return new WorldRef(world.dimension().location().toString());
    }
}
