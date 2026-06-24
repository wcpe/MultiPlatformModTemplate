package top.wcpe.mc.mpmt.core.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import lombok.Value;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/**
 * 会话注册表（服务端，线程安全，FR-28）：握手建立后登记在线会话，提供在线列表 / 查询 / 下线。
 *
 * <p>身份真源以平台连接 / 玩家 UUID 为准（此处用 {@link ConnectionHandle}）；{@code machineCode} 仅作附加弱标签、
 * 非信任凭据（见 SECURITY.md）。命令线程与网络线程可并发读写。
 */
public final class SessionRegistry {

    /** 一条在线会话。 */
    @Value
    public static class Session {
        @NonNull
        ConnectionHandle connection;
        @NonNull
        String sessionId;
        @NonNull
        MachineCode machineCode;
    }

    private final Map<ConnectionHandle, Session> sessions = new ConcurrentHashMap<>();

    /** 登记一条会话（同连接重复登记覆盖）。 */
    public Session register(ConnectionHandle connection, String sessionId, MachineCode machineCode) {
        Objects.requireNonNull(connection, "connection 不能为空");
        Session session = new Session(connection, sessionId, machineCode);
        sessions.put(connection, session);
        return session;
    }

    /** 下线。 */
    public void remove(ConnectionHandle connection) {
        sessions.remove(connection);
    }

    /** 查询某连接的会话。 */
    public Optional<Session> get(ConnectionHandle connection) {
        return Optional.ofNullable(sessions.get(connection));
    }

    /** 在线会话数。 */
    public int onlineCount() {
        return sessions.size();
    }

    /** 全部在线会话（快照）。 */
    public List<Session> all() {
        return new ArrayList<>(sessions.values());
    }
}
