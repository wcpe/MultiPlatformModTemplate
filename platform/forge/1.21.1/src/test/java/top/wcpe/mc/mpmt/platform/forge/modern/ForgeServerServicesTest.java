package top.wcpe.mc.mpmt.platform.forge.modern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.BanService;

/** Forge 1.21.1 服务端闭环装配：共享注册表、网络特性与封禁服务的一致性。 */
class ForgeServerServicesTest {

    private static MpmtRuntime runtimeWithPorts() {
        MpmtRuntime runtime = new MpmtRuntime();
        runtime.ports().register(
                top.wcpe.mc.mpmt.core.domain.port.PersistencePort.class, Fixtures.emptyPersistence());
        runtime.ports().register(
                top.wcpe.mc.mpmt.core.domain.port.SchedulerPort.class, Fixtures.immediateScheduler());
        runtime.ports().register(
                top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort.class,
                Fixtures.recordingConnections(new top.wcpe.mc.mpmt.core.domain.ref.EntityRef(UUID.randomUUID())));
        return runtime;
    }

    @Test
    @DisplayName("装配唯一共享服务组：会话表、网络特性与封禁服务同源")
    void 装配唯一共享服务组() {
        MpmtRuntime runtime = runtimeWithPorts();

        ForgeServerServices services = ForgeServerServices.install(runtime);

        assertNotNull(services.networkFeature());
        assertNotNull(services.banService());
        assertEquals(BanService.State.READY, services.banService().state());
        assertSame(services.networkFeature(), runtime.features().features().get(0));
        assertNotNull(services.networkFeature().sessionRegistry());
    }

    @Test
    @DisplayName("装配拒绝空运行时")
    void 装配拒绝空运行时() {
        assertThrows(NullPointerException.class, () -> ForgeServerServices.install(null));
    }

    @Test
    @DisplayName("同一运行时重复装配被特性注册表明确拒绝，不静默覆盖")
    void 重复装配被拒() {
        MpmtRuntime runtime = runtimeWithPorts();
        ForgeServerServices.install(runtime);

        assertThrows(IllegalArgumentException.class, () -> ForgeServerServices.install(runtime));
    }

    /** 纯 JVM 端口替身：装配测试不依赖 Minecraft / Forge 运行期。 */
    private static final class Fixtures {

        private Fixtures() {
            // 工具类不实例化
        }

        static top.wcpe.mc.mpmt.core.domain.port.PersistencePort emptyPersistence() {
            return new top.wcpe.mc.mpmt.core.domain.port.PersistencePort() {
                @Override
                public java.util.Optional<String> read(String namespace, String key) {
                    return java.util.Optional.empty();
                }

                @Override
                public void write(String namespace, String key, String value) {
                    // 装配测试不持久化
                }
            };
        }

        static top.wcpe.mc.mpmt.core.domain.port.SchedulerPort immediateScheduler() {
            return new top.wcpe.mc.mpmt.core.domain.port.SchedulerPort() {
                @Override
                public void runForEntity(top.wcpe.mc.mpmt.core.domain.ref.EntityRef entity, Runnable task) {
                    task.run();
                }

                @Override
                public void runForLocation(
                        top.wcpe.mc.mpmt.core.domain.ref.WorldRef world, int x, int z, Runnable task) {
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
            };
        }

        static top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort recordingConnections(
                top.wcpe.mc.mpmt.core.domain.ref.EntityRef entity) {
            return new top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort() {
                @Override
                public top.wcpe.mc.mpmt.core.domain.ref.EntityRef entityOf(
                        top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle connection) {
                    return entity;
                }

                @Override
                public void disconnect(
                        top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle connection, String reason) {
                    // 装配测试不断开
                }
            };
        }
    }
}
