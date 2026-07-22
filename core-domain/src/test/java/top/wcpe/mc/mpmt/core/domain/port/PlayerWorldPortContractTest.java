package top.wcpe.mc.mpmt.core.domain.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/** PlayerPort 与 WorldPort 的最小平台无关契约。 */
class PlayerWorldPortContractTest {

    @Test
    void 玩家端口只暴露最小查询契约() throws Exception {
        assertMethod(PlayerPort.class, "isOnline", boolean.class, UUID.class);
        assertMethod(PlayerPort.class, "onlinePlayers", List.class);
        assertMethod(PlayerPort.class, "resolve", Optional.class, UUID.class);
        assertTrue(
                PlayerPort.class.getMethod("onlinePlayers").getGenericReturnType().getTypeName()
                        .contains(PlayerRef.class.getName()));
    }

    @Test
    void 世界端口只暴露最小查询契约() throws Exception {
        assertMethod(WorldPort.class, "isLoaded", boolean.class, String.class);
        assertMethod(WorldPort.class, "loadedWorlds", List.class);
        assertMethod(WorldPort.class, "resolve", Optional.class, String.class);
        assertTrue(
                WorldPort.class.getMethod("loadedWorlds").getGenericReturnType().getTypeName()
                        .contains(WorldRef.class.getName()));
    }

    private static void assertMethod(
            Class<?> owner, String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        assertEquals(returnType, owner.getMethod(name, parameterTypes).getReturnType());
    }

}
