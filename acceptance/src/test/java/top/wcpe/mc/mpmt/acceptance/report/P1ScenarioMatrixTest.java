package top.wcpe.mc.mpmt.acceptance.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P1 必需场景与平台适用矩阵。 */
class P1ScenarioMatrixTest {

    @Test
    @DisplayName("真实平台必含握手、封禁重连、可靠性、心跳、capability、四类 HUD 与真实往返")
    void 真实平台场景完整() {
        List<String> required = P1ScenarioMatrix.requiredFor("paper-fabric");

        assertTrue(required.contains(P1ScenarioMatrix.HANDSHAKE_SUCCESS));
        assertTrue(required.contains(P1ScenarioMatrix.HANDSHAKE_INCOMPATIBLE));
        assertTrue(required.contains(P1ScenarioMatrix.MACHINE_CODE_SESSION));
        assertTrue(required.contains(P1ScenarioMatrix.BAN_RECONNECT));
        assertTrue(required.contains(P1ScenarioMatrix.UNBAN_RECONNECT));
        assertTrue(required.contains(P1ScenarioMatrix.FRAGMENT_CRC));
        assertTrue(required.contains(P1ScenarioMatrix.FRAGMENT_TIMEOUT_RETRY_RESYNC));
        assertTrue(required.contains(P1ScenarioMatrix.SESSION_HEARTBEAT_RTT_TIMEOUT));
        assertTrue(required.contains(P1ScenarioMatrix.CAPABILITY_EVENT_BUS));
        assertTrue(required.contains(P1ScenarioMatrix.HUD_TITLE));
        assertTrue(required.contains(P1ScenarioMatrix.HUD_ACTIONBAR));
        assertTrue(required.contains(P1ScenarioMatrix.HUD_TOAST));
        assertTrue(required.contains(P1ScenarioMatrix.HUD_CHAT));
        assertTrue(required.contains(P1ScenarioMatrix.REAL_ROUND_TRIP));
        assertEquals(required, P1ScenarioMatrix.requiredFor("PAPER-FABRIC"));
    }

    @Test
    @DisplayName("模拟矩阵包含完整 P1 产品核心与集成回环场景")
    void 模拟矩阵场景() {
        List<String> required = P1ScenarioMatrix.requiredFor("sim-forge");

        assertEquals(14, required.size());
        assertTrue(required.contains(P1ScenarioMatrix.HANDSHAKE_SUCCESS));
        assertTrue(required.contains(P1ScenarioMatrix.HANDSHAKE_INCOMPATIBLE));
        assertTrue(required.contains(P1ScenarioMatrix.MACHINE_CODE_SESSION));
        assertTrue(required.contains(P1ScenarioMatrix.BAN_RECONNECT));
        assertTrue(required.contains(P1ScenarioMatrix.UNBAN_RECONNECT));
        assertTrue(required.contains(P1ScenarioMatrix.FRAGMENT_CRC));
        assertTrue(required.contains(P1ScenarioMatrix.FRAGMENT_TIMEOUT_RETRY_RESYNC));
        assertTrue(required.contains(P1ScenarioMatrix.SESSION_HEARTBEAT_RTT_TIMEOUT));
        assertTrue(required.contains(P1ScenarioMatrix.CAPABILITY_EVENT_BUS));
        assertTrue(required.contains(P1ScenarioMatrix.HUD_TITLE));
        assertTrue(required.contains(P1ScenarioMatrix.HUD_ACTIONBAR));
        assertTrue(required.contains(P1ScenarioMatrix.HUD_TOAST));
        assertTrue(required.contains(P1ScenarioMatrix.HUD_CHAT));
        assertTrue(required.contains(P1ScenarioMatrix.INTEGRATED_LOOPBACK));
        assertThrows(UnsupportedOperationException.class, () -> required.add("acceptance/extra"));
    }

    @Test
    @DisplayName("未知平台立即拒绝，不能退化成空清单")
    void 未知平台拒绝() {
        assertThrows(IllegalArgumentException.class, () -> P1ScenarioMatrix.requiredFor("unknown"));
    }
}
