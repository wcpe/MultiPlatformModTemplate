package top.wcpe.mc.mpmt.platform.forge.modern.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 服务端连接句柄严格绑定底层 ServerPlayer 对象身份。 */
class ForgeConnectionHandleTest {

    @Test
    @DisplayName("同一玩家对象相等，不同玩家对象即使未初始化也不相等")
    void 玩家对象身份定义判等() throws ReflectiveOperationException {
        Object firstPlayer = new Object();
        Object secondPlayer = new Object();
        ForgeConnectionHandle left = allocateHandle(firstPlayer);
        ForgeConnectionHandle same = allocateHandle(firstPlayer);
        ForgeConnectionHandle other = allocateHandle(secondPlayer);

        assertEquals(left, same);
        assertEquals(left.hashCode(), same.hashCode());
        assertNotEquals(left, other);
    }

    /** 绕过 Minecraft/Forge 全局注册表初始化，仅注入不透明身份令牌验证判等。 */
    private static ForgeConnectionHandle allocateHandle(Object playerIdentity)
            throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        ForgeConnectionHandle handle =
                (ForgeConnectionHandle) allocateInstance.invoke(unsafe, ForgeConnectionHandle.class);
        Field playerField = ForgeConnectionHandle.class.getDeclaredField("player");
        Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
        long offset = (Long) objectFieldOffset.invoke(unsafe, playerField);
        Method putObject =
                unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
        putObject.invoke(unsafe, handle, offset, playerIdentity);
        return handle;
    }
}
