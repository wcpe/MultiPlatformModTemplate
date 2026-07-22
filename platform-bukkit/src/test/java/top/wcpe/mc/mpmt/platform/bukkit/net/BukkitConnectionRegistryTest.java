package top.wcpe.mc.mpmt.platform.bukkit.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BukkitConnectionRegistryTest {

    @AfterEach
    void 拆除Mock() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void 旧玩家迟到退出不会清除同UUID的新连接() {
        ServerMock server = MockBukkit.mock();
        UUID playerId = UUID.randomUUID();
        PlayerMock oldPlayer = new PlayerMock(server, "旧连接", playerId);
        PlayerMock newPlayer = new PlayerMock(server, "新连接", playerId);
        BukkitConnectionRegistry registry = new BukkitConnectionRegistry();

        BukkitConnectionHandle oldHandle = registry.connected(oldPlayer);
        BukkitConnectionHandle newHandle = registry.connected(newPlayer);

        assertSame(oldHandle, registry.disconnected(oldPlayer));
        assertFalse(registry.isCurrent(oldHandle));
        assertFalse(registry.isCurrent(oldHandle, newPlayer));
        assertTrue(registry.isCurrent(newHandle));
        assertTrue(registry.isCurrent(newHandle, newPlayer));
    }
}
