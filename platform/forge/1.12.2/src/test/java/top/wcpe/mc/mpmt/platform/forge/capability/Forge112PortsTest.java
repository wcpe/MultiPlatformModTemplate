package top.wcpe.mc.mpmt.platform.forge.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/**
 * {@link Forge112MessagePort} / {@link Forge112PlayerPort} / {@link Forge112WorldPort}：
 * 空服（无在线玩家、无已加载世界）下的查询与发消息行为。
 *
 * <p>服务端用 {@code Unsafe.allocateInstance} 造，再经反射把 {@code playerList} / {@code worlds}
 * 设成空集合——这足以覆盖“找不到目标就静默放过”的真实分支，而不需要拉起整个服务端。
 */
class Forge112PortsTest {

    @Test
    void 空服下玩家查询均为空() {
        Forge112PlayerPort port = new Forge112PlayerPort(空服());
        UUID id = UUID.randomUUID();

        assertFalse(port.isOnline(id));
        assertTrue(port.onlinePlayers().isEmpty());
        assertFalse(port.resolve(id).isPresent());
    }

    @Test
    void 空服下世界查询均为空() {
        Forge112WorldPort port = new Forge112WorldPort(空服());

        assertFalse(port.isLoaded("overworld"));
        assertTrue(port.loadedWorlds().isEmpty());
        assertFalse(port.resolve("overworld").isPresent());
    }

    @Test
    void 玩家不在线时发消息是空操作() {
        Forge112MessagePort port = new Forge112MessagePort(空服());

        // 找不到目标玩家就静默放过（不抛、不报警）
        port.send(new PlayerRef(UUID.randomUUID(), "absent"), "hello");
    }

    @Test
    void 服务端为空时三个端口都报错() {
        assertThrows(NullPointerException.class, () -> new Forge112PlayerPort(null));
        assertThrows(NullPointerException.class, () -> new Forge112WorldPort(null));
        assertThrows(NullPointerException.class, () -> new Forge112MessagePort(null));
    }

    private static MinecraftServer 空服() {
        MinecraftServer server = 未初始化(DedicatedServer.class);
        // 1.12.2 的 PlayerList 是抽象类，取它的具体子类；
        // 它同时用列表（在线玩家）与 UUID 索引表（getPlayerByUUID）两套结构，两套都要置空
        DedicatedPlayerList playerList = 未初始化(DedicatedPlayerList.class);
        List<EntityPlayerMP> players = new ArrayList<>();
        设字段(playerList, "playerEntityList", players);
        设字段(playerList, "uuidToPlayerMap", new HashMap<UUID, EntityPlayerMP>());
        设字段(server, "playerList", playerList);
        设字段(server, "worlds", new WorldServer[0]);
        return server;
    }

    private static <T> T 未初始化(Class<T> type) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法构造测试替身：" + type.getName(), error);
        }
    }

    private static void 设字段(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("无法设置字段 " + name, e);
            }
        }
        throw new IllegalStateException("找不到字段 " + name);
    }

    @Test
    void 空集合不被修改() {
        Forge112PlayerPort port = new Forge112PlayerPort(空服());

        assertThrows(UnsupportedOperationException.class, () -> port.onlinePlayers().add(null));
        assertEquals(0, port.onlinePlayers().size());
    }
}
