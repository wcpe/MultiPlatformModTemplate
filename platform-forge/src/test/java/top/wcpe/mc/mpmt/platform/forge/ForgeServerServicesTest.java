package top.wcpe.mc.mpmt.platform.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.BanService;

class ForgeServerServicesTest {

    @Test
    @DisplayName("服务端闭环只装配一组共享注册表、网络特性与封禁服务")
    void 装配唯一共享服务组() {
        MpmtRuntime runtime = runtimeWithPorts();

        ForgeServerServices services = ForgeServerServices.install(runtime);

        assertTrue(services.usesSessionRegistry(services.networkFeature().sessionRegistry()));
        assertSame(services.networkFeature(), runtime.features().features().get(0));
        assertEquals(BanService.State.READY, services.banService().state());
    }

    private static MpmtRuntime runtimeWithPorts() {
        MpmtRuntime runtime = new MpmtRuntime();
        runtime.ports().register(PersistencePort.class, new EmptyPersistence());
        runtime.ports().register(SchedulerPort.class, new ImmediateScheduler());
        runtime.ports().register(ConnectionControlPort.class, new NoopConnections());
        return runtime;
    }

    private static final class EmptyPersistence implements PersistencePort {
        @Override
        public Optional<String> read(String namespace, String key) {
            return Optional.empty();
        }

        @Override
        public void write(String namespace, String key, String value) {
            // 测试不触发写入
        }
    }

    private static final class ImmediateScheduler implements SchedulerPort {
        @Override
        public void runForEntity(EntityRef entity, Runnable task) {
            task.run();
        }

        @Override
        public void runForLocation(WorldRef world, int x, int z, Runnable task) {
            task.run();
        }

        @Override
        public void runGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
            return () -> { };
        }
    }

    private static final class NoopConnections implements ConnectionControlPort {
        @Override
        public EntityRef entityOf(ConnectionHandle connection) {
            throw new UnsupportedOperationException("测试不触发连接查询");
        }

        @Override
        public void disconnect(ConnectionHandle connection, String reason) {
            throw new UnsupportedOperationException("测试不触发断开");
        }
    }
}
