package top.wcpe.mc.mpmt.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;
import top.wcpe.mc.mpmt.protocol.packet.ResyncRequiredPacket;

/** 服务端心跳 nonce、RTT、超时宽限与连接代际测试。 */
class HeartbeatServiceTest {

    @Test
    @DisplayName("Pong 按 nonce 更新 RTT，错误或重复 Pong 不重复更新")
    void 计算Rtt并忽略重复Pong() {
        Fixture fixture = new Fixture();
        TestConnection connection = fixture.register();

        fixture.scheduler.tick();
        PingPacket ping = (PingPacket) fixture.lastPacket();
        fixture.clock.set(40L);
        fixture.receive(connection, new PongPacket(ping.getNonce() + 1L));
        assertEquals(-1L, fixture.sessions.get(connection).get().getRttMillis());

        fixture.receive(connection, new PongPacket(ping.getNonce()));
        assertEquals(40L, fixture.sessions.get(connection).get().getRttMillis());
        fixture.clock.set(90L);
        fixture.receive(connection, new PongPacket(ping.getNonce()));
        assertEquals(40L, fixture.sessions.get(connection).get().getRttMillis());

        fixture.scheduler.tick();
        assertNotEquals(ping.getNonce(), ((PingPacket) fixture.lastPacket()).getNonce());
    }

    @Test
    @DisplayName("首次超时标记重同步并发送要求，宽限期第二次超时才断开且只断一次")
    void 两阶段超时仅断开一次() {
        Fixture fixture = new Fixture();
        TestConnection connection = fixture.register();

        fixture.scheduler.tick();
        fixture.clock.set(100L);
        fixture.scheduler.tick();

        SessionRegistry.Session required = fixture.sessions.get(connection).orElseThrow(AssertionError::new);
        assertEquals(SessionRegistry.State.RESYNC_REQUIRED, required.getState());
        assertEquals(0L, ((ResyncRequiredPacket) fixture.lastPacket()).getAuthoritativeRevision());
        assertTrue(fixture.scheduler.entityTasks.isEmpty());

        fixture.clock.set(199L);
        fixture.scheduler.tick();
        assertTrue(fixture.scheduler.entityTasks.isEmpty());

        fixture.clock.set(200L);
        fixture.scheduler.tick();
        assertEquals(1, fixture.scheduler.entityTasks.size());
        fixture.scheduler.runNextEntity();
        assertEquals(1, fixture.connections.disconnected.size());

        fixture.clock.set(400L);
        fixture.scheduler.tick();
        assertTrue(fixture.scheduler.entityTasks.isEmpty());
        assertEquals(1, fixture.connections.disconnected.size());
    }

    @Test
    @DisplayName("宽限期内完成重同步会清除超时状态并恢复下一轮心跳")
    void 重同步完成取消断开() {
        Fixture fixture = new Fixture();
        TestConnection connection = fixture.register();

        fixture.scheduler.tick();
        fixture.clock.set(100L);
        fixture.scheduler.tick();
        SessionRegistry.Session required = fixture.sessions.get(connection).orElseThrow(AssertionError::new);
        SessionRegistry.Session complete =
                fixture.sessions.markResyncComplete(required, 3L).orElseThrow(AssertionError::new);
        fixture.service.onResyncComplete(complete);

        fixture.clock.set(200L);
        fixture.scheduler.tick();
        assertTrue(fixture.scheduler.entityTasks.isEmpty());
        assertTrue(fixture.lastPacket() instanceof PingPacket);
        assertEquals(SessionRegistry.State.RESYNC_COMPLETE, fixture.sessions.get(connection).get().getState());
    }

    @Test
    @DisplayName("迟到的断开任务不会断开同 UUID 的新物理连接")
    void 迟到断线不影响新连接() {
        Fixture fixture = new Fixture();
        UUID playerId = UUID.randomUUID();
        TestConnection oldConnection = fixture.register(playerId, "old");

        fixture.scheduler.tick();
        fixture.clock.set(100L);
        fixture.scheduler.tick();
        fixture.clock.set(200L);
        fixture.scheduler.tick();
        assertEquals(1, fixture.scheduler.entityTasks.size());

        TestConnection newConnection = fixture.register(playerId, "new");
        fixture.scheduler.runNextEntity();

        assertTrue(fixture.connections.disconnected.isEmpty());
        assertTrue(fixture.sessions.get(newConnection).isPresent());
        assertFalse(fixture.sessions.get(oldConnection).isPresent());
    }

    @Test
    @DisplayName("关闭服务会取消实例定时器并停止后续心跳")
    void 关闭释放定时器() {
        Fixture fixture = new Fixture();
        fixture.register();

        fixture.service.close();

        assertTrue(fixture.scheduler.timer.closed);
        fixture.scheduler.tick();
        assertTrue(fixture.transport.sends.isEmpty());
    }

    private static final class Fixture {
        private final AtomicLong clock = new AtomicLong();
        private final SessionRegistry sessions = new SessionRegistry();
        private final FakeTransport transport = new FakeTransport();
        private final PacketCodec codec = new PacketCodec();
        private final PacketDispatcher dispatcher = new PacketDispatcher(transport, codec);
        private final ManualScheduler scheduler = new ManualScheduler();
        private final FakeConnectionControl connections = new FakeConnectionControl();
        private final HeartbeatService service =
                new HeartbeatService(sessions, dispatcher, scheduler, connections, clock::get, 1L, 100L, 100L);

        private TestConnection register() {
            return register(UUID.randomUUID(), "session");
        }

        private TestConnection register(UUID playerId, String sessionId) {
            TestConnection connection = new TestConnection(playerId);
            sessions.register(connection, sessionId, new MachineCode("machine"));
            return connection;
        }

        private void receive(ConnectionHandle connection, Packet packet) {
            transport.receiver.accept(connection, codec.encode(packet));
        }

        private Packet lastPacket() {
            return codec.decode(transport.sends.get(transport.sends.size() - 1));
        }
    }

    private static final class FakeTransport implements TransportPort {
        private final List<byte[]> sends = new ArrayList<>();
        private BiConsumer<ConnectionHandle, byte[]> receiver;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            sends.add(data);
        }

        @Override
        public void send(byte[] data) {
            throw new UnsupportedOperationException("服务端不支持无连接发送");
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 32767;
        }
    }

    private static final class ManualScheduler implements SchedulerPort {
        private final Deque<Runnable> entityTasks = new ArrayDeque<>();
        private final TimerHandle timer = new TimerHandle();
        private Runnable timerTask;

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
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
            timerTask = task;
            return timer;
        }

        private void tick() {
            if (!timer.closed) {
                timerTask.run();
            }
        }

        private void runNextEntity() {
            entityTasks.removeFirst().run();
        }
    }

    private static final class TimerHandle implements AutoCloseable {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
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
