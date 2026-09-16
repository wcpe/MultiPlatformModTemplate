package top.wcpe.mc.mpmt.platform.fabric.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.platform.fabric.MinecraftTestSupport;

/** Fabric 玩家端口：在线列表与按 UUID 解析，未在线玩家不得误报。 */
class FabricPlayerPortTest {

    @Test
    @DisplayName("在线玩家按 UUID 解析，离线玩家返回空")
    void 在线与离线玩家查询() {
        MinecraftServer server = MinecraftTestSupport.newServer();
        UUID onlineId = UUID.randomUUID();
        ServerPlayer online = MinecraftTestSupport.newPlayer(onlineId, "在线玩家");
        MinecraftTestSupport.installPlayerList(server, online);

        FabricPlayerPort port = new FabricPlayerPort(server);

        assertTrue(port.isOnline(onlineId));
        PlayerRef resolved = port.resolve(onlineId).orElseThrow();
        assertEquals(onlineId, resolved.getUuid());
        assertEquals("在线玩家", resolved.getName());
        assertEquals(List.of(resolved), port.onlinePlayers());

        UUID offlineId = UUID.randomUUID();
        assertFalse(port.isOnline(offlineId));
        assertTrue(port.resolve(offlineId).isEmpty());
    }

    @Test
    @DisplayName("在线列表不可变，多玩家按在线顺序暴露")
    void 在线列表只读且有序() {
        MinecraftServer server = MinecraftTestSupport.newServer();
        ServerPlayer first = MinecraftTestSupport.newPlayer(UUID.randomUUID(), "甲");
        ServerPlayer second = MinecraftTestSupport.newPlayer(UUID.randomUUID(), "乙");
        MinecraftTestSupport.installPlayerList(server, first, second);

        FabricPlayerPort port = new FabricPlayerPort(server);

        assertEquals(List.of("甲", "乙"), port.onlinePlayers().stream().map(PlayerRef::getName).toList());
        assertThrows(UnsupportedOperationException.class, () -> port.onlinePlayers().add(null));
    }

    @Test
    @DisplayName("服务端为空失败快")
    void 服务端为空失败快() {
        assertThrows(NullPointerException.class, () -> new FabricPlayerPort(null));
    }
}
