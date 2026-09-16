package top.wcpe.mc.mpmt.platform.forge;

import java.util.Optional;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/** 纯 JVM 端口替身：供服务端闭环装配类测试复用（不依赖 Minecraft / Forge 运行期）。 */
public final class ForgePortFixtures {

    private ForgePortFixtures() {
        // 工具类不实例化
    }

    /** 空持久化：读恒空、写丢弃（装配类测试只关心端口已注册）。 */
    public static PersistencePort emptyPersistence() {
        return new PersistencePort() {
            @Override
            public Optional<String> read(String namespace, String key) {
                return Optional.empty();
            }

            @Override
            public void write(String namespace, String key, String value) {
                // 装配测试不触发持久化
            }
        };
    }

    /** 立即执行调度器：异步任务在当前线程同步完成，便于确定性断言。 */
    public static SchedulerPort immediateScheduler() {
        return new SchedulerPort() {
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
        };
    }

    /** 只读连接控制：查询返回固定实体，断开抛异常（装配测试不触发断开）。 */
    public static ConnectionControlPort noopConnections(EntityRef entity) {
        return new ConnectionControlPort() {
            @Override
            public EntityRef entityOf(ConnectionHandle connection) {
                return entity;
            }

            @Override
            public void disconnect(ConnectionHandle connection, String reason) {
                throw new UnsupportedOperationException("装配测试不触发断开");
            }
        };
    }

    /** 记录断开调用的连接控制替身。 */
    public static final class RecordingConnections implements ConnectionControlPort {

        private final EntityRef entity;
        private int disconnectCalls;
        private String lastReason;

        public RecordingConnections(EntityRef entity) {
            this.entity = entity;
        }

        @Override
        public EntityRef entityOf(ConnectionHandle connection) {
            return entity;
        }

        @Override
        public void disconnect(ConnectionHandle connection, String reason) {
            disconnectCalls++;
            lastReason = reason;
        }

        public int disconnectCalls() {
            return disconnectCalls;
        }

        public String lastReason() {
            return lastReason;
        }
    }

    /** 无实体的数据目录替身（仅用于生命周期装配）。 */
    public static DataDirectoryPort emptyDataDirectory() {
        return () -> java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "mpmt-fixture");
    }
}
