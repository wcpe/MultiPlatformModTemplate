package top.wcpe.mc.mpmt.platform.forge.modern.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import top.wcpe.mc.mpmt.platform.forge.modern.Forge121MinecraftTestSupport;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeConnectionHandle;

/** Forge 1.21.1 世界 / 玩家 / 消息 / 持久化 / 连接控制的真实行为。 */
class ForgeCapabilityPortsTest {

    @Test
    @DisplayName("世界端口按维度键解析、枚举已加载世界，未知维度判否")
    void 世界端口按维度解析() {
        MinecraftServer server = Forge121MinecraftTestSupport.newServer();
        Forge121MinecraftTestSupport.putLevel(server, Forge121MinecraftTestSupport.newLevel("minecraft:overworld"));
        Forge121MinecraftTestSupport.putLevel(server, Forge121MinecraftTestSupport.newLevel("minecraft:the_end"));
        WorldPort port = new ForgeWorldPort(server);

        assertTrue(port.isLoaded("minecraft:overworld"));
        assertTrue(port.isLoaded("minecraft:the_end"));
        assertFalse(port.isLoaded("minecraft:the_nether"));

        Optional<WorldRef> resolved = port.resolve("minecraft:the_end");
        assertTrue(resolved.isPresent());
        assertEquals("minecraft:the_end", resolved.orElseThrow().getId());
        assertFalse(port.resolve("minecraft:missing").isPresent());

        List<WorldRef> worlds = port.loadedWorlds();
        assertEquals(2, worlds.size());
    }

    @Test
    @DisplayName("玩家端口按 UUID 查询在线玩家并转换为平台无关引用")
    void 玩家端口按UUID解析() {
        ServerPlayer online = Forge121MinecraftTestSupport.newPlayer(UUID.randomUUID(), "在线玩家");
        MinecraftServer server = Forge121MinecraftTestSupport.newServer();
        Forge121MinecraftTestSupport.installPlayerList(server, online);
        ForgePlayerPort port = new ForgePlayerPort(server);

        assertTrue(port.isOnline(online.getUUID()));
        assertFalse(port.isOnline(UUID.randomUUID()));

        Optional<PlayerRef> resolved = port.resolve(online.getUUID());
        assertTrue(resolved.isPresent());
        assertEquals("在线玩家", resolved.orElseThrow().getName());
        assertFalse(port.resolve(UUID.randomUUID()).isPresent());

        List<PlayerRef> players = port.onlinePlayers();
        assertEquals(1, players.size());
        assertEquals(online.getUUID(), players.get(0).getUuid());
    }

    @Test
    @DisplayName("消息端口忽略不在线玩家、不抛异常")
    void 消息端口忽略离线玩家() {
        MinecraftServer server = Forge121MinecraftTestSupport.newServer();
        Forge121MinecraftTestSupport.installPlayerList(
                server, Forge121MinecraftTestSupport.newPlayer(UUID.randomUUID(), "在线"));

        new ForgeMessagePort(server).send(new PlayerRef(UUID.randomUUID(), "离线玩家"), "不会被投递");
    }

    @Test
    @DisplayName("连接控制端口把句柄转为实体引用，离线玩家断开时静默")
    void 连接控制端口实体引用与离线断开() {
        ServerPlayer player = Forge121MinecraftTestSupport.newPlayer(UUID.randomUUID(), "句柄玩家");
        MinecraftServer server = Forge121MinecraftTestSupport.newServer();
        ForgeConnectionControlPort port = new ForgeConnectionControlPort(server);

        assertEquals(player.getUUID(), port.entityOf(new ForgeConnectionHandle(player)).getId());

        // 玩家不在在线列表：断开静默返回，不抛异常
        port.disconnect(new ForgeConnectionHandle(player), "测试断开");
    }

    @Test
    @DisplayName("持久化端口按命名空间落盘并原样读回，缺失键返回空")
    void 持久化端口读回一致(@TempDir Path tempDirectory) throws Exception {
        ForgePersistencePort port = new ForgePersistencePort(() -> tempDirectory);

        port.write("alpha", "key", "值一");
        port.write("alpha", "second", "值二");
        port.write("beta", "key", "另一命名空间");

        assertEquals(Optional.of("值一"), port.read("alpha", "key"));
        assertEquals(Optional.of("值二"), port.read("alpha", "second"));
        assertEquals(Optional.of("另一命名空间"), port.read("beta", "key"));
        assertFalse(port.read("alpha", "missing").isPresent());
        assertFalse(port.read("gamma", "key").isPresent());
        assertTrue(Files.exists(tempDirectory.resolve("data").resolve("alpha.properties")));
    }

    @Test
    @DisplayName("端口构造均拒绝空依赖")
    void 构造拒绝空依赖() {
        assertThrows(NullPointerException.class, () -> new ForgeWorldPort(null));
        assertThrows(NullPointerException.class, () -> new ForgePlayerPort(null));
        assertThrows(NullPointerException.class, () -> new ForgeMessagePort(null));
        assertThrows(NullPointerException.class, () -> new ForgeConnectionControlPort(null));
        assertThrows(NullPointerException.class, () -> new ForgePersistencePort(null));
    }

    @Test
    @DisplayName("数据目录端口落在 Forge 配置目录的 mpmt 子目录")
    void 数据目录端口落在配置目录() {
        Path configDirectory = Forge121MinecraftTestSupport.fixtureDirectory("config");
        Forge121MinecraftTestSupport.replaceConfigDirectory(configDirectory);

        assertEquals(configDirectory.resolve("mpmt"), new ForgeDataDirectoryPort().baseDirectory());
    }
}
