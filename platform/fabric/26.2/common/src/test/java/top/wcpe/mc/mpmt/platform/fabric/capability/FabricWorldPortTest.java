package top.wcpe.mc.mpmt.platform.fabric.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.platform.fabric.MinecraftTestSupport;

/** Fabric 世界端口：按维度资源标识暴露当前已加载世界，并在缺失时返回空。 */
class FabricWorldPortTest {

    @Test
    @DisplayName("已加载世界按维度标识暴露，缺失维度返回空且不误报已加载")
    void 世界查询契约() {
        MinecraftServer server = MinecraftTestSupport.newServer();
        ServerLevel overworld = MinecraftTestSupport.newLevel("minecraft:overworld");
        ServerLevel nether = MinecraftTestSupport.newLevel("minecraft:the_nether");
        MinecraftTestSupport.putLevel(server, overworld);
        MinecraftTestSupport.putLevel(server, nether);

        FabricWorldPort port = new FabricWorldPort(server);

        List<WorldRef> worlds = port.loadedWorlds();
        assertEquals(2, worlds.size());
        assertEquals(
                List.of("minecraft:overworld", "minecraft:the_nether"),
                worlds.stream().map(WorldRef::getId).sorted().toList());
        assertEquals("minecraft:overworld", port.resolve("minecraft:overworld").orElseThrow().getId());
        assertTrue(port.isLoaded("minecraft:the_nether"));
        assertFalse(port.isLoaded("minecraft:the_end"));
        assertTrue(port.resolve("minecraft:the_end").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> port.loadedWorlds().add(new WorldRef("x")));
    }

    @Test
    @DisplayName("无已加载世界时返回空列表而非空指针")
    void 无世界时返回空列表() {
        FabricWorldPort port = new FabricWorldPort(MinecraftTestSupport.newServer());

        assertTrue(port.loadedWorlds().isEmpty());
        assertFalse(port.isLoaded("minecraft:overworld"));
    }

    @Test
    @DisplayName("服务端为空失败快")
    void 服务端为空失败快() {
        assertThrows(NullPointerException.class, () -> new FabricWorldPort(null));
    }
}
