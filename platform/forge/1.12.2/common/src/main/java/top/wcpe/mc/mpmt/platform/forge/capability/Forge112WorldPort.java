package top.wcpe.mc.mpmt.platform.forge.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import top.wcpe.mc.mpmt.kernel.port.WorldPort;
import top.wcpe.mc.mpmt.kernel.ref.WorldRef;

/**
 * Forge 1.12.2 世界查询端口：按维度名暴露当前已加载世界。
 *
 * <p>1.12.2 的维度标识没有 1.16+ 的资源位置，故取 {@code DimensionType.getName()}
 * （{@code overworld} / {@code the_nether} / {@code the_end} 或 mod 自定义名），
 * 与 {@code WorldRef.id} 的「世界名 / 维度 id」语义一致。
 */
public final class Forge112WorldPort implements WorldPort {

    private final MinecraftServer server;

    /**
     * 构造。
     *
     * @param server 当前服务端实例，不可为空
     */
    public Forge112WorldPort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    @Override
    public boolean isLoaded(String worldId) {
        return resolve(worldId).isPresent();
    }

    @Override
    public List<WorldRef> loadedWorlds() {
        List<WorldRef> worlds = new ArrayList<>();
        for (WorldServer world : server.worlds) {
            worlds.add(toRef(world));
        }
        return Collections.unmodifiableList(worlds);
    }

    @Override
    public Optional<WorldRef> resolve(String worldId) {
        for (WorldServer world : server.worlds) {
            WorldRef ref = toRef(world);
            if (ref.getId().equals(worldId)) {
                return Optional.of(ref);
            }
        }
        return Optional.empty();
    }

    private static WorldRef toRef(WorldServer world) {
        return new WorldRef(world.provider.getDimensionType().getName());
    }
}
