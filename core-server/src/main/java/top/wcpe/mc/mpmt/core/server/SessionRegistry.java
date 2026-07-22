package top.wcpe.mc.mpmt.core.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import lombok.NonNull;
import lombok.Value;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/** 会话注册表：以不可变会话快照承载连接代际、重同步状态、RTT 与修订号。 */
public final class SessionRegistry {

    /** 会话同步状态。 */
    public enum State {
        ESTABLISHED,
        RESYNC_REQUIRED,
        RESYNC_COMPLETE
    }

    /** 一条不可变在线会话快照。 */
    @Value
    public static class Session {
        @NonNull
        ConnectionHandle connection;
        @NonNull
        String sessionId;
        @NonNull
        MachineCode machineCode;
        @NonNull
        State state;
        long rttMillis;
        long revision;
        long generation;
    }

    private static final long UNKNOWN_RTT_MILLIS = -1L;

    private final Map<ConnectionHandle, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong();

    /** 登记一条新物理会话；同 UUID 连接会获得更高 generation 并替换旧会话。 */
    public Session register(ConnectionHandle connection, String sessionId, MachineCode machineCode) {
        Objects.requireNonNull(connection, "connection 不能为空");
        Session session = new Session(
                connection,
                sessionId,
                machineCode,
                State.ESTABLISHED,
                UNKNOWN_RTT_MILLIS,
                0L,
                generationSequence.incrementAndGet());
        sessions.put(connection, session);
        return session;
    }

    /** 下线；旧物理连接的迟到事件不会移除同 UUID 的新会话。 */
    public void remove(ConnectionHandle connection) {
        Objects.requireNonNull(connection, "connection 不能为空");
        sessions.computeIfPresent(
                connection,
                (key, current) -> sameConnection(current.getConnection(), connection) ? null : current);
    }

    /** 新物理连接建立时清除同 UUID 旧会话。 */
    void removeForReconnect(ConnectionHandle connection) {
        Objects.requireNonNull(connection, "connection 不能为空");
        sessions.remove(connection);
    }

    /** 查询当前物理连接的会话。 */
    public Optional<Session> get(ConnectionHandle connection) {
        Objects.requireNonNull(connection, "connection 不能为空");
        Session current = sessions.get(connection);
        return current != null && sameConnection(current.getConnection(), connection)
                ? Optional.of(current)
                : Optional.empty();
    }

    /** 判断快照是否仍属于当前物理会话；状态字段更新不会使同 generation 快照失效。 */
    public boolean isCurrent(Session session) {
        Objects.requireNonNull(session, "session 不能为空");
        return sameGeneration(sessions.get(session.getConnection()), session);
    }

    /** 判断连接与会话编号是否仍指向当前物理会话。 */
    public boolean isCurrent(ConnectionHandle connection, String sessionId) {
        Objects.requireNonNull(connection, "connection 不能为空");
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Session current = sessions.get(connection);
        return current != null
                && sameConnection(current.getConnection(), connection)
                && current.getSessionId().equals(sessionId);
    }

    /** 条件更新当前 generation 的 RTT。 */
    public Optional<Session> updateRtt(Session expected, long rttMillis) {
        if (rttMillis < 0L) {
            throw new IllegalArgumentException("rttMillis 不能为负数");
        }
        return update(expected, current -> copy(current, current.getState(), rttMillis, current.getRevision()));
    }

    /** 条件标记当前 generation 需要重同步。 */
    public Optional<Session> markResyncRequired(Session expected) {
        return update(
                expected,
                current -> copy(current, State.RESYNC_REQUIRED, current.getRttMillis(), current.getRevision()));
    }

    /** 条件标记当前 generation 完成重同步并推进修订号。 */
    public Optional<Session> markResyncComplete(Session expected, long revision) {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision 不能为负数");
        }
        return update(
                expected,
                current -> copy(
                        current,
                        State.RESYNC_COMPLETE,
                        current.getRttMillis(),
                        Math.max(current.getRevision(), revision)));
    }

    /** 按机器码查询当前在线会话（不可变快照）。 */
    public List<Session> findByMachineCode(MachineCode machineCode) {
        Objects.requireNonNull(machineCode, "machineCode 不能为空");
        List<Session> matched = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (session.getMachineCode().equals(machineCode)) {
                matched.add(session);
            }
        }
        return Collections.unmodifiableList(matched);
    }

    /** 在线会话数。 */
    public int onlineCount() {
        return sessions.size();
    }

    /** 全部在线会话（不可变快照）。 */
    public List<Session> all() {
        return Collections.unmodifiableList(new ArrayList<>(sessions.values()));
    }

    private Optional<Session> update(Session expected, UnaryOperator<Session> updater) {
        Objects.requireNonNull(expected, "expected 不能为空");
        AtomicReference<Session> result = new AtomicReference<>();
        sessions.compute(
                expected.getConnection(),
                (key, current) -> updateIfCurrent(current, expected, updater, result));
        return Optional.ofNullable(result.get());
    }

    private static Session updateIfCurrent(
            Session current,
            Session expected,
            UnaryOperator<Session> updater,
            AtomicReference<Session> result) {
        if (!sameGeneration(current, expected)) {
            return current;
        }
        Session updated = updater.apply(current);
        result.set(updated);
        return updated;
    }

    private static Session copy(Session source, State state, long rttMillis, long revision) {
        return new Session(
                source.getConnection(),
                source.getSessionId(),
                source.getMachineCode(),
                state,
                rttMillis,
                revision,
                source.getGeneration());
    }

    /** 物理连接必须按对象身份比较，不能把同 UUID 的新句柄视为旧连接。 */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static boolean sameConnection(ConnectionHandle left, ConnectionHandle right) {
        return left == right;
    }

    private static boolean sameGeneration(Session current, Session expected) {
        return current != null
                && sameConnection(current.getConnection(), expected.getConnection())
                && current.getGeneration() == expected.getGeneration();
    }
}
