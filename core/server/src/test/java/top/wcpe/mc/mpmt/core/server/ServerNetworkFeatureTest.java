package top.wcpe.mc.mpmt.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.ProtocolVersion;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ClientIdReportPacket;
import top.wcpe.mc.mpmt.protocol.packet.DisconnectPacket;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;
import top.wcpe.mc.mpmt.protocol.packet.ResyncRequestPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/**
 * 服务端网络装配特性（FR-19）：经运行时装配 TransportPort → dispatcher → 握手服务 + Ping/Pong 示例，
 * 平台无关、纯 JVM 穷举（同一份装配将在各平台经各自 TransportPort 复用）。
 */
class ServerNetworkFeatureTest {

    private final PacketCodec codec = new PacketCodec();

    private static ConnectionHandle conn() {
        return new ConnectionHandle() {
        };
    }

    /** 启用一个装配好服务端网络的运行时。 */
    private ServerNetworkFeature enabled(FakeServerTransport transport) {
        return enabled(transport, new SessionRegistry(), new FakeScheduler());
    }

    private ServerNetworkFeature enabled(
            FakeServerTransport transport, SessionRegistry sessions, FakeScheduler scheduler) {
        return enabled(
                transport,
                sessions,
                scheduler,
                new FakeConnectionControl(),
                new BanRegistry(),
                () -> BanService.State.READY);
    }

    private ServerNetworkFeature enabled(
            FakeServerTransport transport,
            SessionRegistry sessions,
            FakeScheduler scheduler,
            FakeConnectionControl connections,
            BanRegistry bans,
            Supplier<BanService.State> banState) {
        MpmtRuntime runtime = new MpmtRuntime();
        runtime.ports().register(TransportPort.class, transport);
        runtime.ports().register(SchedulerPort.class, scheduler);
        runtime.ports().register(ConnectionControlPort.class, connections);
        AtomicLong seq = new AtomicLong();
        ServerNetworkFeature feature = new ServerNetworkFeature(
                bans, () -> "s-" + seq.incrementAndGet(), sessions, banState);
        runtime.features().register(feature);
        runtime.enable();
        return feature;
    }

    private Packet lastSent(FakeServerTransport transport) {
        return codec.decode(transport.sends.get(transport.sends.size() - 1));
    }

    @Test
    @DisplayName("装配后：握手（含标识上报欢迎）+ Ping/Pong 往返")
    void 装配握手与往返() {
        FakeServerTransport transport = new FakeServerTransport();
        ServerNetworkFeature feature = enabled(transport);
        ConnectionHandle c = conn();

        // 客户端发兼容 ClientHello → 服务端回 ServerHello(accepted)
        transport.receive(c, codec.encode(new ClientHelloPacket(ProtocolVersion.CURRENT, "1.0.0")));
        ServerHelloPacket hello = (ServerHelloPacket) lastSent(transport);
        assertTrue(hello.isAccepted());

        // 客户端上报标识 → 服务端回欢迎，会话建立
        transport.receive(c, codec.encode(new ClientIdReportPacket("honest")));
        Packet welcomePacket = codec.decode(transport.sends.get(1));
        assertTrue(welcomePacket instanceof ServerMessagePacket);
        ServerMessagePacket welcome = (ServerMessagePacket) welcomePacket;
        assertEquals("欢迎", welcome.getText());
        assertEquals(HandshakeStateMachine.State.ESTABLISHED, feature.handshakeService().stateOf(c));
        assertTrue(feature.sessionRegistry().get(c).isPresent());
    }

    @Test
    @DisplayName("装配产物在启用后可取，启用前取即拒")
    void 装配产物可见性() {
        SessionRegistry sessions = new SessionRegistry();
        ServerNetworkFeature feature =
                new ServerNetworkFeature(new BanRegistry(), () -> "s", sessions);
        assertThrows(IllegalStateException.class, feature::handshakeService);
        assertThrows(IllegalStateException.class, feature::hudMessageService);
        assertThrows(IllegalStateException.class, feature::heartbeatService);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerNetworkFeature(new BanRegistry(), () -> "legacy"));

        FakeServerTransport transport = new FakeServerTransport();
        feature = enabled(transport, sessions, new FakeScheduler());
        assertNotNull(feature.handshakeService());
        assertNotNull(feature.hudMessageService());
        assertNotNull(feature.heartbeatService());
        assertSame(sessions, feature.sessionRegistry());
        assertEquals("server-network", feature.name());
    }

    @Test
    @DisplayName("服务端心跳接收 Pong 后更新共享会话 RTT")
    void 心跳更新共享会话() {
        FakeServerTransport transport = new FakeServerTransport();
        SessionRegistry sessions = new SessionRegistry();
        FakeScheduler scheduler = new FakeScheduler();
        ServerNetworkFeature feature = enabled(transport, sessions, scheduler);
        ConnectionHandle connection = conn();
        establish(transport, connection);

        scheduler.tick();
        PingPacket ping = (PingPacket) lastSent(transport);
        transport.receive(connection, codec.encode(new PongPacket(ping.getNonce())));

        assertSame(sessions, feature.sessionRegistry());
        assertTrue(sessions.get(connection).get().getRttMillis() >= 0L);
        feature.onDisable(null);
        assertTrue(scheduler.timer.closed);
    }

    @Test
    @DisplayName("重连重同步：收到 ResyncRequest 后标记同一会话完成")
    void 重同步请求服务端重发状态() {
        FakeServerTransport transport = new FakeServerTransport();
        SessionRegistry sessions = new SessionRegistry();
        ServerNetworkFeature feature = enabled(transport, sessions, new FakeScheduler());
        ConnectionHandle connection = conn();
        establish(transport, connection);
        assertNotNull(feature.resyncCoordinator());

        transport.receive(connection, codec.encode(new ResyncRequestPacket(42L)));

        ServerMessagePacket resent = (ServerMessagePacket) lastSent(transport);
        assertTrue(resent.getText().contains("42"));
        SessionRegistry.Session session = sessions.get(connection).orElseThrow(AssertionError::new);
        assertEquals(SessionRegistry.State.RESYNC_COMPLETE, session.getState());
        assertEquals(42L, session.getRevision());
    }

    @Test
    @DisplayName("封禁拒绝会调度真实断开且迟到任务不误断同 UUID 新连接")
    void 封禁真实断开并校验物理连接() {
        FakeServerTransport transport = new FakeServerTransport();
        SessionRegistry sessions = new SessionRegistry();
        FakeScheduler scheduler = new FakeScheduler();
        FakeConnectionControl connections = new FakeConnectionControl();
        BanRegistry bans = new BanRegistry();
        bans.ban(new MachineCode("blocked"), "测试封禁");
        ServerNetworkFeature feature = enabled(
                transport,
                sessions,
                scheduler,
                connections,
                bans,
                () -> BanService.State.READY);
        UUID playerId = UUID.randomUUID();
        EqualConnection oldConnection = new EqualConnection(playerId);

        feature.onConnected(oldConnection);
        establish(transport, oldConnection, "blocked");
        assertTrue(lastSent(transport) instanceof DisconnectPacket);
        assertTrue(!sessions.get(oldConnection).isPresent());
        assertEquals(1, scheduler.entityTasks.size());
        assertTrue(connections.disconnected.isEmpty());

        EqualConnection newConnection = new EqualConnection(playerId);
        feature.onConnected(newConnection);
        scheduler.runNextEntity();
        assertTrue(connections.disconnected.isEmpty());

        establish(transport, newConnection, "blocked");
        scheduler.runNextEntity();
        assertEquals(1, connections.disconnected.size());
        assertSame(newConnection, connections.disconnected.get(0));
    }

    @Test
    @DisplayName("构造入参为空即拒")
    void 入参校验() {
        SessionRegistry sessions = new SessionRegistry();
        assertThrows(NullPointerException.class, () -> new ServerNetworkFeature(null, () -> "s", sessions));
        assertThrows(
                NullPointerException.class,
                () -> new ServerNetworkFeature(new BanRegistry(), null, sessions));
        assertThrows(
                NullPointerException.class,
                () -> new ServerNetworkFeature(new BanRegistry(), () -> "s", null));
        assertThrows(
                NullPointerException.class,
                () -> new ServerNetworkFeature(
                        new BanRegistry(), () -> "s", sessions, null));
    }

    private void establish(FakeServerTransport transport, ConnectionHandle connection) {
        establish(transport, connection, "honest");
    }

    private void establish(
            FakeServerTransport transport, ConnectionHandle connection, String machineCode) {
        transport.receive(
                connection,
                codec.encode(new ClientHelloPacket(ProtocolVersion.CURRENT, "1.0.0")));
        transport.receive(connection, codec.encode(new ClientIdReportPacket(machineCode)));
    }

    /** 假服务端传输：记录发送、可注入收到的字节。 */
    private static final class FakeServerTransport implements TransportPort {
        final List<byte[]> sends = new ArrayList<>();
        private BiConsumer<ConnectionHandle, byte[]> receiver;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            sends.add(data);
        }

        @Override
        public void send(byte[] data) {
            throw new UnsupportedOperationException("服务端传输不支持无连接发送");
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            this.receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 32767;
        }

        /** 模拟从某连接收到字节。 */
        void receive(ConnectionHandle connection, byte[] data) {
            receiver.accept(connection, data);
        }
    }

    private static final class FakeScheduler implements SchedulerPort {
        private final Deque<Runnable> entityTasks = new ArrayDeque<>();
        private final TimerHandle timer = new TimerHandle();
        private Runnable timerTask;

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
            timerTask = task;
            return timer;
        }

        private void tick() {
            timerTask.run();
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
            UUID entityId = connection instanceof EqualConnection
                    ? ((EqualConnection) connection).playerId
                    : UUID.randomUUID();
            return new EntityRef(entityId);
        }

        @Override
        public void disconnect(ConnectionHandle connection, String reason) {
            disconnected.add(connection);
        }
    }

    private static final class EqualConnection implements ConnectionHandle {
        private final UUID playerId;

        private EqualConnection(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualConnection
                    && playerId.equals(((EqualConnection) other).playerId);
        }

        @Override
        public int hashCode() {
            return playerId.hashCode();
        }
    }
}
