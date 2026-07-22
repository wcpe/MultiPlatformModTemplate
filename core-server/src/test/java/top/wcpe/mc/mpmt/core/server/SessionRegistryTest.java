package top.wcpe.mc.mpmt.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/** 会话注册表登记 / 查询 / 下线穷举（FR-28）。 */
class SessionRegistryTest {

    private static ConnectionHandle conn() {
        return new ConnectionHandle() {
        };
    }

    @Test
    @DisplayName("登记后可查、在线数正确，下线后清除")
    void 登记查询下线() {
        SessionRegistry registry = new SessionRegistry();
        ConnectionHandle c = conn();

        registry.register(c, "s-1", new MachineCode("code-1"));
        assertTrue(registry.get(c).isPresent());
        assertEquals("s-1", registry.get(c).get().getSessionId());
        assertEquals("code-1", registry.get(c).get().getMachineCode().getValue());
        assertEquals(1, registry.onlineCount());

        registry.remove(c);
        assertFalse(registry.get(c).isPresent());
        assertEquals(0, registry.onlineCount());
    }

    @Test
    @DisplayName("多会话与全量快照")
    void 多会话() {
        SessionRegistry registry = new SessionRegistry();
        registry.register(conn(), "s-1", new MachineCode("a"));
        registry.register(conn(), "s-2", new MachineCode("b"));
        assertEquals(2, registry.onlineCount());
        assertEquals(2, registry.all().size());
    }

    @Test
    @DisplayName("同连接重复登记覆盖")
    void 重复登记覆盖() {
        SessionRegistry registry = new SessionRegistry();
        ConnectionHandle c = conn();
        registry.register(c, "s-1", new MachineCode("a"));
        registry.register(c, "s-2", new MachineCode("b"));
        assertEquals(1, registry.onlineCount());
        assertEquals("s-2", registry.get(c).get().getSessionId());
    }

    @Test
    @DisplayName("可按机器码查询且会话快照不可修改")
    void 按机器码查询不可变快照() {
        SessionRegistry registry = new SessionRegistry();
        registry.register(conn(), "s-1", new MachineCode("a"));
        registry.register(conn(), "s-2", new MachineCode("a"));
        registry.register(conn(), "s-3", new MachineCode("b"));

        assertEquals(2, registry.findByMachineCode(new MachineCode("a")).size());
        assertThrows(UnsupportedOperationException.class, () -> registry.findByMachineCode(new MachineCode("a")).clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.all().clear());
    }

    @Test
    @DisplayName("会话状态、RTT、修订号通过条件更新生成新不可变快照")
    void 条件更新会话状态() {
        SessionRegistry registry = new SessionRegistry();
        ConnectionHandle connection = conn();
        SessionRegistry.Session established =
                registry.register(connection, "s-1", new MachineCode("a"));

        assertEquals(SessionRegistry.State.ESTABLISHED, established.getState());
        assertEquals(-1L, established.getRttMillis());
        assertEquals(0L, established.getRevision());
        assertTrue(established.getGeneration() > 0L);

        SessionRegistry.Session withRtt = registry.updateRtt(established, 42L).orElseThrow(AssertionError::new);
        assertEquals(42L, withRtt.getRttMillis());
        SessionRegistry.Session required =
                registry.markResyncRequired(established).orElseThrow(AssertionError::new);
        assertEquals(SessionRegistry.State.RESYNC_REQUIRED, required.getState());
        SessionRegistry.Session complete =
                registry.markResyncComplete(established, 7L).orElseThrow(AssertionError::new);
        assertEquals(SessionRegistry.State.RESYNC_COMPLETE, complete.getState());
        assertEquals(7L, complete.getRevision());
        assertTrue(registry.isCurrent(established));
    }

    @Test
    @DisplayName("同 UUID 新物理连接替换旧会话且旧断开不能移除当前会话")
    void 当前会话校验() {
        SessionRegistry registry = new SessionRegistry();
        UUID playerId = UUID.randomUUID();
        EqualConnection oldConnection = new EqualConnection(playerId);
        SessionRegistry.Session oldSession = registry.register(oldConnection, "old", new MachineCode("a"));
        EqualConnection newConnection = new EqualConnection(playerId);
        SessionRegistry.Session newSession = registry.register(newConnection, "new", new MachineCode("b"));

        assertFalse(registry.isCurrent(oldSession));
        assertTrue(registry.isCurrent(newSession));
        assertTrue(newSession.getGeneration() > oldSession.getGeneration());
        assertFalse(registry.updateRtt(oldSession, 10L).isPresent());
        assertFalse(registry.get(oldConnection).isPresent());
        assertTrue(registry.get(newConnection).isPresent());

        registry.remove(oldConnection);
        assertTrue(registry.isCurrent(newSession));
        registry.remove(newConnection);
        assertFalse(registry.isCurrent(newSession));
    }

    private static final class EqualConnection implements ConnectionHandle {
        private final UUID playerId;

        private EqualConnection(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualConnection && playerId.equals(((EqualConnection) other).playerId);
        }

        @Override
        public int hashCode() {
            return playerId.hashCode();
        }
    }
}
