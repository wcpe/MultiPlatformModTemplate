package top.wcpe.mc.mpmt.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ban.BanEntry;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/** 封禁服务异步初始化、持久化顺序、串行变更与真实断开测试。 */
class BanServiceTest {

    @Test
    @DisplayName("异步初始化成功后原子装入快照并进入 READY")
    void 初始化成功() {
        Fixture fixture = new Fixture();
        fixture.persistence.value = Optional.of(fixture.codec.encode(entries(entry("b", "二"), entry("a", "一"))));

        CompletableFuture<Void> future = fixture.service.initialize();
        assertEquals(BanService.State.INITIALIZING, fixture.service.state());
        assertFalse(future.isDone());

        fixture.scheduler.runNextAsync();
        assertEquals(BanService.State.READY, fixture.service.state());
        assertEquals(entries(entry("a", "一"), entry("b", "二")), fixture.registry.list());
        assertTrue(future.isDone());
    }

    @Test
    @DisplayName("初始化读取到损坏快照进入 FAILED 且异常对调用方可见")
    void 初始化失败() {
        Fixture fixture = new Fixture();
        fixture.persistence.value = Optional.of("broken");

        CompletableFuture<Void> future = fixture.service.initialize();
        fixture.scheduler.runNextAsync();

        assertEquals(BanService.State.FAILED, fixture.service.state());
        assertThrows(CompletionException.class, future::join);
        assertTrue(fixture.registry.list().isEmpty());
    }

    @Test
    @DisplayName("封禁先持久化再更新注册表并按实体归属断开当前会话")
    void 持久化后封禁并断开() {
        Fixture fixture = readyFixture();
        MachineCode code = new MachineCode("machine-a");
        TestConnection connection = new TestConnection(UUID.randomUUID());
        fixture.sessions.register(connection, "session-a", code);
        fixture.persistence.beforeWrite = () -> assertFalse(fixture.registry.isBanned(code));

        CompletableFuture<Void> future = fixture.service.ban(code, "作弊");
        fixture.scheduler.runNextAsync();

        assertTrue(future.isDone());
        assertTrue(fixture.registry.isBanned(code));
        assertEquals(1, fixture.persistence.writeCount);
        assertTrue(fixture.connections.disconnected.isEmpty());
        assertEquals(1, fixture.scheduler.entityTasks.size());

        fixture.scheduler.runNextEntity();
        assertEquals(entries(connection), fixture.connections.disconnected);
    }

    @Test
    @DisplayName("持久化失败不更新注册表、不踢会话且异常不被吞掉")
    void 持久化失败不提交内存状态() {
        Fixture fixture = readyFixture();
        MachineCode code = new MachineCode("machine-a");
        fixture.persistence.writeFailure = new IllegalStateException("磁盘不可写");

        CompletableFuture<Void> future = fixture.service.ban(code, "作弊");
        fixture.scheduler.runNextAsync();

        assertThrows(CompletionException.class, future::join);
        assertFalse(fixture.registry.isBanned(code));
        assertTrue(fixture.scheduler.entityTasks.isEmpty());
        assertEquals(BanService.State.READY, fixture.service.state());
    }

    @Test
    @DisplayName("变更严格串行且延迟踢出前会校验仍为当前会话")
    void 串行变更与当前会话校验() {
        Fixture fixture = readyFixture();
        MachineCode code = new MachineCode("machine-a");
        UUID playerId = UUID.randomUUID();
        TestConnection oldConnection = new TestConnection(playerId);
        fixture.sessions.register(oldConnection, "old", code);

        CompletableFuture<Void> ban = fixture.service.ban(code, "作弊");
        CompletableFuture<Void> unban = fixture.service.unban(code);
        fixture.scheduler.runNextAsync();
        assertTrue(ban.isDone());
        assertFalse(unban.isDone());

        TestConnection newConnection = new TestConnection(playerId);
        fixture.sessions.register(newConnection, "new", code);
        fixture.scheduler.runNextEntity();
        assertTrue(fixture.connections.disconnected.isEmpty());

        fixture.scheduler.runNextAsync();
        assertTrue(unban.isDone());
        assertFalse(fixture.registry.isBanned(code));
        assertEquals(2, fixture.persistence.writeCount);
    }

    private static Fixture readyFixture() {
        Fixture fixture = new Fixture();
        fixture.service.initialize();
        fixture.scheduler.runNextAsync();
        return fixture;
    }

    private static BanEntry entry(String code, String reason) {
        return new BanEntry(new MachineCode(code), reason);
    }

    @SafeVarargs
    private static <T> List<T> entries(T... values) {
        List<T> result = new ArrayList<>();
        java.util.Collections.addAll(result, values);
        return result;
    }

    private static final class Fixture {
        private final BanRegistry registry = new BanRegistry();
        private final SessionRegistry sessions = new SessionRegistry();
        private final FakePersistence persistence = new FakePersistence();
        private final ManualScheduler scheduler = new ManualScheduler();
        private final FakeConnectionControl connections = new FakeConnectionControl();
        private final BanSnapshotCodec codec = new BanSnapshotCodec();
        private final BanService service =
                new BanService(registry, sessions, persistence, scheduler, connections, codec);
    }

    private static final class FakePersistence implements PersistencePort {
        private Optional<String> value = Optional.empty();
        private RuntimeException writeFailure;
        private Runnable beforeWrite = () -> {
        };
        private int writeCount;

        @Override
        public Optional<String> read(String namespace, String key) {
            return value;
        }

        @Override
        public void write(String namespace, String key, String value) {
            beforeWrite.run();
            if (writeFailure != null) {
                throw writeFailure;
            }
            this.value = Optional.of(value);
            writeCount++;
        }
    }

    private static final class ManualScheduler implements SchedulerPort {
        private final Deque<Runnable> asyncTasks = new ArrayDeque<>();
        private final Deque<Runnable> entityTasks = new ArrayDeque<>();

        @Override
        public void runForEntity(EntityRef entity, Runnable task) {
            entityTasks.addLast(task);
        }

        @Override
        public void runForLocation(WorldRef world, int x, int z, Runnable task) {
            throw new UnsupportedOperationException("测试未使用坐标调度");
        }

        @Override
        public void runGlobal(Runnable task) {
            throw new UnsupportedOperationException("测试未使用全局调度");
        }

        @Override
        public void runAsync(Runnable task) {
            asyncTasks.addLast(task);
        }

        @Override
        public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
            throw new UnsupportedOperationException("测试未使用周期调度");
        }

        private void runNextAsync() {
            asyncTasks.removeFirst().run();
        }

        private void runNextEntity() {
            entityTasks.removeFirst().run();
        }
    }

    private static final class FakeConnectionControl implements ConnectionControlPort {
        private final List<ConnectionHandle> disconnected = new ArrayList<>();

        @Override
        public EntityRef entityOf(ConnectionHandle connection) {
            return new EntityRef(((TestConnection) connection).playerId);
        }

        @Override
        public void disconnect(ConnectionHandle connection, String reason) {
            disconnected.add(connection);
        }
    }

    private static final class TestConnection implements ConnectionHandle {
        private final UUID playerId;

        private TestConnection(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestConnection && playerId.equals(((TestConnection) other).playerId);
        }

        @Override
        public int hashCode() {
            return playerId.hashCode();
        }
    }
}
