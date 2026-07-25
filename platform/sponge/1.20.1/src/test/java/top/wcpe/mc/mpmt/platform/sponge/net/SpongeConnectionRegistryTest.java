package top.wcpe.mc.mpmt.platform.sponge.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;

class SpongeConnectionRegistryTest {

    @Test
    void 旧玩家迟到退出不会清除同UUID的新连接() {
        UUID playerId = UUID.randomUUID();
        ServerPlayer oldPlayer = player(playerId);
        ServerPlayer newPlayer = player(playerId);
        SpongeConnectionRegistry registry = new SpongeConnectionRegistry();

        SpongeConnectionHandle oldHandle = registry.connected(oldPlayer);
        SpongeConnectionHandle newHandle = registry.connected(newPlayer);

        assertSame(oldHandle, registry.disconnected(oldPlayer));
        assertFalse(registry.isCurrent(oldHandle));
        assertFalse(registry.isCurrent(oldHandle, newPlayer));
        assertTrue(registry.isCurrent(newHandle));
        assertTrue(registry.isCurrent(newHandle, newPlayer));
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static ServerPlayer player(UUID playerId) {
        return (ServerPlayer) Proxy.newProxyInstance(
                ServerPlayer.class.getClassLoader(),
                new Class<?>[] {ServerPlayer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("uniqueId")) {
                        return playerId;
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    return null;
                });
    }
}
