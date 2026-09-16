package top.wcpe.mc.mpmt.platform.forge.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.wcpe.mc.mpmt.core.domain.port.WorldPort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.platform.forge.ForgeMinecraftTestSupport;

/** Forge 世界 / 玩家 / 消息 / 持久化 / 数据目录端口的真实行为。 */
class ForgePortsTest {

    @Test
    @DisplayName("世界端口按维度键解析、枚举已加载世界，未知维度判否")
    void 世界端口按维度解析() {
        MinecraftServer server = ForgeMinecraftTestSupport.newServer();
        ForgeMinecraftTestSupport.putLevel(server, ForgeMinecraftTestSupport.newLevel("minecraft:overworld"));
        ForgeMinecraftTestSupport.putLevel(server, ForgeMinecraftTestSupport.newLevel("minecraft:the_nether"));
        WorldPort port = new ForgeWorldPort(server);

        assertTrue(port.isLoaded("minecraft:overworld"));
        assertTrue(port.isLoaded("minecraft:the_nether"));
        assertFalse(port.isLoaded("minecraft:the_end"));

        Optional<WorldRef> resolved = port.resolve("minecraft:the_nether");
        assertTrue(resolved.isPresent());
        assertEquals("minecraft:the_nether", resolved.orElseThrow().getId());
        assertFalse(port.resolve("minecraft:missing").isPresent());

        List<WorldRef> worlds = port.loadedWorlds();
        assertEquals(2, worlds.size());
        assertTrue(worlds.stream().anyMatch(ref -> ref.getId().equals("minecraft:overworld")));
    }

    @Test
    @DisplayName("玩家端口按 UUID 查询在线玩家并转换为平台无关引用")
    void 玩家端口按UUID解析() {
        ServerPlayer online = ForgeMinecraftTestSupport.newPlayer(UUID.randomUUID(), "在线玩家");
        MinecraftServer server = ForgeMinecraftTestSupport.newServer();
        ForgeMinecraftTestSupport.installPlayerList(server, online);
        ForgePlayerPort port = new ForgePlayerPort(server);

        assertTrue(port.isOnline(online.getUUID()));
        assertFalse(port.isOnline(UUID.randomUUID()));

        Optional<PlayerRef> resolved = port.resolve(online.getUUID());
        assertTrue(resolved.isPresent());
        assertEquals(online.getUUID(), resolved.orElseThrow().getUuid());
        assertEquals("在线玩家", resolved.orElseThrow().getName());
        assertFalse(port.resolve(UUID.randomUUID()).isPresent());

        List<PlayerRef> players = port.onlinePlayers();
        assertEquals(1, players.size());
        assertEquals(online.getUUID(), players.get(0).getUuid());
    }

    @Test
    @DisplayName("消息端口忽略不在线玩家、不抛异常")
    void 消息端口忽略离线玩家() {
        MinecraftServer server = ForgeMinecraftTestSupport.newServer();
        ForgeMinecraftTestSupport.installPlayerList(server, ForgeMinecraftTestSupport.newPlayer(UUID.randomUUID(), "在线"));

        new ForgeMessagePort(server).send(new PlayerRef(UUID.randomUUID(), "离线玩家"), "不会被投递");
    }

    @Test
    @DisplayName("持久化端口按命名空间落盘并原样读回，缺失键返回空")
    void 持久化端口读回一致(@TempDir Path tempDirectory) throws Exception {
        ForgePersistencePort port = new ForgePersistencePort(() -> tempDirectory);

        port.write("alpha", "key", "值一");
        port.write("alpha", "second", "值二");
        port.write("beta", "key", "另一个命名空间");

        assertEquals(Optional.of("值一"), port.read("alpha", "key"));
        assertEquals(Optional.of("值二"), port.read("alpha", "second"));
        assertEquals(Optional.of("另一个命名空间"), port.read("beta", "key"));
        assertFalse(port.read("alpha", "missing").isPresent());
        assertFalse(port.read("gamma", "key").isPresent());
        assertTrue(Files.exists(tempDirectory.resolve("data").resolve("alpha.properties")));
    }

    @Test
    @DisplayName("数据目录端口落在 Forge 配置目录的 mpmt 子目录")
    void 数据目录落在配置目录下() throws Exception {
        Path configDirectory = Path.of(System.getProperty("java.io.tmpdir"), "mpmt-forge-datadir-" + UUID.randomUUID());
        ForgeMinecraftTestSupport.replaceConfigDirectory(configDirectory);

        assertEquals(configDirectory.resolve("mpmt"), new ForgeDataDirectoryPort().baseDirectory());
    }
}
