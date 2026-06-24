package top.wcpe.mc.mpmt.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
