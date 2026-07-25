package top.wcpe.mc.mpmt.platform.fabric.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.Field;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * Fabric 连接句柄相等性契约（tip：按玩家 UUID）。
 *
 * <p>P2 曾按 {@code ServerPlayer} 身份相等；tip 文档与会话表键稳定要求按 UUID，本测覆盖 tip 语义。
 */
class FabricConnectionHandleTest {

    @Test
    @DisplayName("同 UUID 的不同 ServerPlayer 包装相等；不同 UUID 不等")
    void 按UUID相等() throws Exception {
        UUID shared = UUID.randomUUID();
        ServerPlayer firstPlayer = allocatePlayer(shared);
        ServerPlayer sameUuidPlayer = allocatePlayer(shared);
        ServerPlayer otherPlayer = allocatePlayer(UUID.randomUUID());

        FabricConnectionHandle first = new FabricConnectionHandle(firstPlayer);
        FabricConnectionHandle sameUuid = new FabricConnectionHandle(sameUuidPlayer);
        FabricConnectionHandle other = new FabricConnectionHandle(otherPlayer);

        assertEquals(first, sameUuid, "同 UUID 的新包装应相等（会话表键稳定）");
        assertEquals(first.hashCode(), sameUuid.hashCode());
        assertNotEquals(first, other, "不同 UUID 必须视为不同连接");
        assertEquals(shared, first.playerId());
    }

    private static ServerPlayer allocatePlayer(UUID id) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        ServerPlayer player = (ServerPlayer) unsafe.allocateInstance(ServerPlayer.class);
        // 写入 UUID：优先匹配常见字段名，失败则扫描 UUID 类型字段
        if (!tryWriteUuid(player, id, "uuid") && !tryWriteUuid(player, id, "id")) {
            boolean written = false;
            for (Field f : walkFields(ServerPlayer.class)) {
                if (f.getType() == UUID.class) {
                    f.setAccessible(true);
                    f.set(player, id);
                    written = true;
                    break;
                }
            }
            if (!written) {
                throw new IllegalStateException("无法向 ServerPlayer 写入 UUID 字段");
            }
        }
        return player;
    }

    private static boolean tryWriteUuid(ServerPlayer player, UUID id, String name) {
        try {
            Field f = findField(ServerPlayer.class, name);
            if (f == null || f.getType() != UUID.class) {
                return false;
            }
            f.setAccessible(true);
            f.set(player, id);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Field[] walkFields(Class<?> type) {
        java.util.List<Field> fields = new java.util.ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                fields.add(f);
            }
            current = current.getSuperclass();
        }
        return fields.toArray(new Field[0]);
    }
}
