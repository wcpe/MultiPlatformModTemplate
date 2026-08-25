package top.wcpe.mc.mpmt.platform.fabric.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;
import top.wcpe.mc.mpmt.domain.capability.PlayerLeftEvent;

class FabricCapabilityBootstrapLifecycleTest {

    @Test
    void 全局连接回调只路由当前运行时并在停服后清除() throws Exception {
        MpmtRuntime first = new MpmtRuntime();
        MpmtRuntime second = new MpmtRuntime();
        AtomicInteger firstEvents = new AtomicInteger();
        AtomicInteger secondEvents = new AtomicInteger();
        first.eventBus().subscribe(PlayerJoinedEvent.class, event -> firstEvents.incrementAndGet());
        first.eventBus().subscribe(PlayerLeftEvent.class, event -> firstEvents.incrementAndGet());
        second.eventBus().subscribe(PlayerJoinedEvent.class, event -> secondEvents.incrementAndGet());
        second.eventBus().subscribe(PlayerLeftEvent.class, event -> secondEvents.incrementAndGet());

        Method activate = method("activateRuntime", MpmtRuntime.class);
        Method clear = method("clearRuntime", MpmtRuntime.class);
        Method publishJoined = method("publishJoined", PlayerRef.class);
        Method publishLeft = method("publishLeft", PlayerRef.class);
        PlayerRef player = new PlayerRef(UUID.randomUUID(), "测试玩家");

        try {
            activate.invoke(null, first);
            publishJoined.invoke(null, player);
            publishLeft.invoke(null, player);
            assertEquals(2, firstEvents.get());

            activate.invoke(null, second);
            publishJoined.invoke(null, player);
            publishLeft.invoke(null, player);
            assertEquals(2, firstEvents.get());
            assertEquals(2, secondEvents.get());

            clear.invoke(null, first);
            publishJoined.invoke(null, player);
            publishLeft.invoke(null, player);
            assertEquals(4, secondEvents.get());

            clear.invoke(null, second);
            publishJoined.invoke(null, player);
            publishLeft.invoke(null, player);
            assertEquals(4, secondEvents.get());
        } finally {
            clear.invoke(null, first);
            clear.invoke(null, second);
        }
    }

    private static Method method(String name, Class<?> parameterType) throws Exception {
        Method method = FabricCapabilityBootstrap.class.getDeclaredMethod(name, parameterType);
        method.setAccessible(true);
        return method;
    }
}
