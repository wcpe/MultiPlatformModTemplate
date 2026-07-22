package top.wcpe.mc.mpmt.core.server;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;
import top.wcpe.mc.mpmt.protocol.packet.ResyncRequiredPacket;

/** 服务端心跳：周期探测、RTT 计算、两阶段超时重同步与真实断开。 */
public final class HeartbeatService implements AutoCloseable {

    public static final long DEFAULT_INTERVAL_TICKS = 100L;
    public static final long DEFAULT_TIMEOUT_MILLIS = 15_000L;
    public static final long DEFAULT_GRACE_MILLIS = 15_000L;

    private static final Logger LOGGER = Logger.getLogger(HeartbeatService.class.getName());
    private static final String TIMEOUT_REASON = "客户端心跳超时";

    private enum Stage {
        AWAITING_PONG,
        RESYNC_GRACE,
        DISCONNECT_SCHEDULED
    }

    private final SessionRegistry sessions;
    private final PacketDispatcher dispatcher;
    private final SchedulerPort scheduler;
    private final ConnectionControlPort connections;
    private final LongSupplier clock;
    private final long timeoutMillis;
    private final long graceMillis;
    private final Map<ConnectionHandle, PendingHeartbeat> pending = new ConcurrentHashMap<>();
    private final AtomicLong nonceSequence = new AtomicLong();
    private final AutoCloseable timer;

    private volatile boolean closed;

    public HeartbeatService(
            SessionRegistry sessions,
            PacketDispatcher dispatcher,
            SchedulerPort scheduler,
            ConnectionControlPort connections) {
        this(
                sessions,
                dispatcher,
                scheduler,
                connections,
                System::currentTimeMillis,
                DEFAULT_INTERVAL_TICKS,
                DEFAULT_TIMEOUT_MILLIS,
                DEFAULT_GRACE_MILLIS);
    }

    HeartbeatService(
            SessionRegistry sessions,
            PacketDispatcher dispatcher,
            SchedulerPort scheduler,
            ConnectionControlPort connections,
            LongSupplier clock,
            long intervalTicks,
            long timeoutMillis,
            long graceMillis) {
        this.sessions = Objects.requireNonNull(sessions, "sessions 不能为空");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler 不能为空");
        this.connections = Objects.requireNonNull(connections, "connections 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        requirePositive(intervalTicks, "intervalTicks");
        this.timeoutMillis = requirePositive(timeoutMillis, "timeoutMillis");
        this.graceMillis = requirePositive(graceMillis, "graceMillis");
        dispatcher.on(PacketIds.PONG, this::onPongPacket);
        this.timer = Objects.requireNonNull(
                scheduler.runTimer(intervalTicks, intervalTicks, this::tick),
                "timer 不能为空");
    }

    /** 连接断开时仅清理该物理连接自己的待处理心跳。 */
    public void onDisconnected(ConnectionHandle connection) {
        Objects.requireNonNull(connection, "connection 不能为空");
        pending.computeIfPresent(
                connection,
                (key, current) -> sameConnection(current.connection, connection) ? null : current);
    }

    /** 重同步完成后清除该 generation 的超时宽限状态。 */
    public void onResyncComplete(SessionRegistry.Session session) {
        Objects.requireNonNull(session, "session 不能为空");
        pending.computeIfPresent(
                session.getConnection(),
                (key, current) -> current.matches(session) ? null : current);
    }

    private void tick() {
        if (closed) {
            return;
        }
        dispatcher.tickReliability();
        long now = clock.getAsLong();
        for (SessionRegistry.Session session : sessions.all()) {
            tickSession(session, now);
        }
    }

    private void tickSession(SessionRegistry.Session session, long now) {
        PendingHeartbeat current = pending.get(session.getConnection());
        if (current == null || !current.matches(session)) {
            installPing(session, current, now);
            return;
        }
        if (current.stage == Stage.AWAITING_PONG
                && elapsed(now, current.stageStartedAt) >= timeoutMillis) {
            requireResync(session, current, now);
            return;
        }
        if (current.stage == Stage.RESYNC_GRACE
                && elapsed(now, current.stageStartedAt) >= graceMillis) {
            scheduleDisconnect(session, current);
        }
    }

    private void installPing(
            SessionRegistry.Session session, PendingHeartbeat previous, long now) {
        PendingHeartbeat next = PendingHeartbeat.awaiting(session, nonceSequence.incrementAndGet(), now);
        boolean installed = previous == null
                ? pending.putIfAbsent(session.getConnection(), next) == null
                : pending.replace(session.getConnection(), previous, next);
        if (!installed) {
            return;
        }
        if (!sessions.isCurrent(session)) {
            pending.remove(session.getConnection(), next);
            return;
        }
        dispatcher.send(session.getConnection(), new PingPacket(next.nonce));
    }

    private void requireResync(
            SessionRegistry.Session session, PendingHeartbeat current, long now) {
        PendingHeartbeat grace = current.withStage(Stage.RESYNC_GRACE, now);
        if (!pending.replace(session.getConnection(), current, grace)) {
            return;
        }
        sessions.markResyncRequired(session).ifPresent(
                updated -> dispatcher.send(
                        updated.getConnection(),
                        new ResyncRequiredPacket(updated.getRevision())));
    }

    private void scheduleDisconnect(
            SessionRegistry.Session session, PendingHeartbeat current) {
        PendingHeartbeat scheduled = current.withStage(Stage.DISCONNECT_SCHEDULED, current.stageStartedAt);
        if (!pending.replace(session.getConnection(), current, scheduled)) {
            return;
        }
        EntityRef entity = connections.entityOf(session.getConnection());
        scheduler.runForEntity(entity, () -> disconnectIfStillTimedOut(session));
    }

    private void disconnectIfStillTimedOut(SessionRegistry.Session session) {
        if (!sessions.isCurrent(session)) {
            return;
        }
        SessionRegistry.Session current = sessions.get(session.getConnection()).orElse(null);
        if (current != null && current.getState() == SessionRegistry.State.RESYNC_REQUIRED) {
            connections.disconnect(session.getConnection(), TIMEOUT_REASON);
        }
    }

    private void onPongPacket(ConnectionHandle connection, Packet packet) {
        if (closed) {
            return;
        }
        PongPacket pong = (PongPacket) packet;
        PendingHeartbeat current = pending.get(connection);
        if (current == null || current.nonce != pong.getNonce()) {
            return;
        }
        SessionRegistry.Session session = sessions.get(connection).orElse(null);
        if (session == null || !current.matches(session)) {
            return;
        }
        if (pending.remove(connection, current)) {
            sessions.updateRtt(session, elapsed(clock.getAsLong(), current.sentAt));
        }
    }

    /** 关闭并释放实例级定时器；可重复调用。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        pending.clear();
        try {
            timer.close();
        } catch (Exception error) {
            LOGGER.log(Level.SEVERE, "关闭服务端心跳定时器失败", error);
            throw new IllegalStateException("关闭服务端心跳定时器失败", error);
        }
    }

    private static long elapsed(long now, long startedAt) {
        return Math.max(0L, now - startedAt);
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " 必须大于零");
        }
        return value;
    }

    /** 物理连接必须按对象身份比较，防止旧连接清理新连接心跳。 */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static boolean sameConnection(ConnectionHandle left, ConnectionHandle right) {
        return left == right;
    }

    private static final class PendingHeartbeat {
        private final ConnectionHandle connection;
        private final long generation;
        private final long nonce;
        private final long sentAt;
        private final Stage stage;
        private final long stageStartedAt;

        private PendingHeartbeat(
                ConnectionHandle connection,
                long generation,
                long nonce,
                long sentAt,
                Stage stage,
                long stageStartedAt) {
            this.connection = connection;
            this.generation = generation;
            this.nonce = nonce;
            this.sentAt = sentAt;
            this.stage = stage;
            this.stageStartedAt = stageStartedAt;
        }

        private static PendingHeartbeat awaiting(
                SessionRegistry.Session session, long nonce, long now) {
            return new PendingHeartbeat(
                    session.getConnection(),
                    session.getGeneration(),
                    nonce,
                    now,
                    Stage.AWAITING_PONG,
                    now);
        }

        private PendingHeartbeat withStage(Stage nextStage, long now) {
            return new PendingHeartbeat(connection, generation, nonce, sentAt, nextStage, now);
        }

        private boolean matches(SessionRegistry.Session session) {
            return sameConnection(connection, session.getConnection())
                    && generation == session.getGeneration();
        }
    }
}
