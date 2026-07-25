package top.wcpe.mc.mpmt.platform.fabric.gametest.sim;

import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTest;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.core.client.HandshakeClientService;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.core.server.HandshakeServerService;
import top.wcpe.mc.mpmt.core.server.HeartbeatService;
import top.wcpe.mc.mpmt.core.server.SessionRegistry;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;

/** Fabric 模拟服场景共享夹具；全部实现仅存在于 gametest 源集。 */
final class SimTestSupport {

    static final String SUITE = "acceptance";
    static final String MACHINE_CODE = "sim-machine-code";

    private SimTestSupport() {
        // 工具类不实例化
    }

    static ServerGameTest test(String id, ScenarioAction action) {
        return new ServerGameTest() {
            @Override
            public String suite() {
                return SUITE;
            }

            @Override
            public String id() {
                return id;
            }

            @Override
            public void run(ServerGameTestContext context) {
                action.run(context);
            }
        };
    }

    static String largeText(String token) {
        StringBuilder text = new StringBuilder(token);
        while (text.length() < 600) {
            text.append('-').append(token);
        }
        return text.toString();
    }

    static HeartbeatService heartbeat(
            SessionRegistry sessions,
            PacketDispatcher dispatcher,
            SchedulerPort scheduler,
            ConnectionControlPort connections,
            LongSupplier clock,
            long timeoutMillis,
            long graceMillis) {
        try {
            Constructor<HeartbeatService> constructor = HeartbeatService.class.getDeclaredConstructor(
                    SessionRegistry.class,
                    PacketDispatcher.class,
                    SchedulerPort.class,
                    ConnectionControlPort.class,
                    LongSupplier.class,
                    long.class,
                    long.class,
                    long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    sessions, dispatcher, scheduler, connections, clock, 1L, timeoutMillis, graceMillis);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法创建短周期心跳验收夹具", error);
        }
    }

    @FunctionalInterface
    interface ScenarioAction {
        void run(ServerGameTestContext context);
    }

    static final class HandshakeFixture {
        final LoopbackTransport loop = new LoopbackTransport();
        final PacketCodec codec = new PacketCodec();
        final PacketDispatcher serverDispatcher = new PacketDispatcher(loop.server(), codec);
        final BanRegistry bans = new BanRegistry();
        final SessionRegistry sessions = new SessionRegistry();
        final AtomicInteger disconnects = new AtomicInteger();
        final HandshakeServerService server;
        PacketDispatcher clientDispatcher;
        HandshakeClientService client;
        private int sessionSequence;

        HandshakeFixture() {
            server = new HandshakeServerService(
                    serverDispatcher,
                    () -> "sim-session-" + (++sessionSequence),
                    bans,
                    sessions,
                    () -> BanService.State.READY,
                    (connection, reason, currentCheck) -> {
                        if (currentCheck.getAsBoolean()) {
                            disconnects.incrementAndGet();
                        }
                    });
            server.onConnected(loop.clientConnection());
            newClient();
        }

        void start() {
            client.startHandshake();
        }

        void reconnect() {
            server.onConnected(loop.reconnect());
            newClient();
        }

        private void newClient() {
            clientDispatcher = new PacketDispatcher(loop.client(), codec);
            client = new HandshakeClientService(clientDispatcher, "sim-1.0", () -> MACHINE_CODE);
        }
    }

    static final class ManualScheduler implements SchedulerPort {
        final List<Runnable> timers = new ArrayList<>();
        final Deque<Runnable> entityTasks = new ArrayDeque<>();
        final List<TimerHandle> handles = new ArrayList<>();

        @Override
        public void runForEntity(EntityRef entity, Runnable task) {
            entityTasks.addLast(task);
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
            TimerHandle handle = new TimerHandle();
            timers.add(task);
            handles.add(handle);
            return handle;
        }

        void tick() {
            for (int index = 0; index < timers.size(); index++) {
                if (!handles.get(index).closed) {
                    timers.get(index).run();
                }
            }
        }

        void runEntityTasks() {
            while (!entityTasks.isEmpty()) {
                entityTasks.removeFirst().run();
            }
        }
    }

    static final class TimerHandle implements AutoCloseable {
        boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    static final class RecordingConnections implements ConnectionControlPort {
        final List<String> disconnectReasons = new ArrayList<>();

        @Override
        public EntityRef entityOf(ConnectionHandle connection) {
            return new EntityRef(UUID.nameUUIDFromBytes(connection.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Override
        public void disconnect(ConnectionHandle connection, String reason) {
            disconnectReasons.add(reason);
        }
    }

    static final class MemoryPersistence implements PersistencePort {
        final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<String> read(String namespace, String key) {
            return Optional.ofNullable(values.get(namespace + '/' + key));
        }

        @Override
        public void write(String namespace, String key, String value) {
            values.put(namespace + '/' + key, value);
        }
    }

    static final class RecordingMessage implements MessagePort {
        final List<String> messages = new ArrayList<>();

        @Override
        public void send(PlayerRef player, String text) {
            messages.add(player.getUuid() + ":" + text);
        }
    }

    static final class Clock {
        final AtomicLong now = new AtomicLong();

        void set(long value) {
            now.set(value);
        }
    }
}
