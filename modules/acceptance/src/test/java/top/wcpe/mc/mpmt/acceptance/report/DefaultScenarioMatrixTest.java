package top.wcpe.mc.mpmt.acceptance.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 默认轨必需场景与平台适用矩阵。 */
class DefaultScenarioMatrixTest {

    @Test
    @DisplayName("真实平台必含握手、封禁重连、可靠性、心跳、capability、四类 HUD 与真实往返")
    void 真实平台场景完整() {
        List<String> required = DefaultScenarioMatrix.requiredFor("paper-fabric");

        assertTrue(required.contains(DefaultScenarioMatrix.HANDSHAKE_SUCCESS));
        assertTrue(required.contains(DefaultScenarioMatrix.HANDSHAKE_INCOMPATIBLE));
        assertTrue(required.contains(DefaultScenarioMatrix.MACHINE_CODE_SESSION));
        assertTrue(required.contains(DefaultScenarioMatrix.BAN_RECONNECT));
        assertTrue(required.contains(DefaultScenarioMatrix.UNBAN_RECONNECT));
        assertTrue(required.contains(DefaultScenarioMatrix.FRAGMENT_CRC));
        assertTrue(required.contains(DefaultScenarioMatrix.FRAGMENT_TIMEOUT_RETRY_RESYNC));
        assertTrue(required.contains(DefaultScenarioMatrix.SESSION_HEARTBEAT_RTT_TIMEOUT));
        assertTrue(required.contains(DefaultScenarioMatrix.CAPABILITY_EVENT_BUS));
        assertTrue(required.contains(DefaultScenarioMatrix.HUD_TITLE));
        assertTrue(required.contains(DefaultScenarioMatrix.HUD_ACTIONBAR));
        assertTrue(required.contains(DefaultScenarioMatrix.HUD_TOAST));
        assertTrue(required.contains(DefaultScenarioMatrix.HUD_CHAT));
        assertTrue(required.contains(DefaultScenarioMatrix.REAL_ROUND_TRIP));
        assertEquals(required, DefaultScenarioMatrix.requiredFor("PAPER-FABRIC"));
    }

    @Test
    @DisplayName("模拟矩阵包含完整产品核心与集成回环场景")
    void 模拟矩阵场景() {
        List<String> required = DefaultScenarioMatrix.requiredFor("sim-forge");

        assertEquals(14, required.size());
        assertTrue(required.contains(DefaultScenarioMatrix.HANDSHAKE_SUCCESS));
        assertTrue(required.contains(DefaultScenarioMatrix.HANDSHAKE_INCOMPATIBLE));
        assertTrue(required.contains(DefaultScenarioMatrix.MACHINE_CODE_SESSION));
        assertTrue(required.contains(DefaultScenarioMatrix.BAN_RECONNECT));
        assertTrue(required.contains(DefaultScenarioMatrix.UNBAN_RECONNECT));
        assertTrue(required.contains(DefaultScenarioMatrix.FRAGMENT_CRC));
        assertTrue(required.contains(DefaultScenarioMatrix.FRAGMENT_TIMEOUT_RETRY_RESYNC));
        assertTrue(required.contains(DefaultScenarioMatrix.SESSION_HEARTBEAT_RTT_TIMEOUT));
        assertTrue(required.contains(DefaultScenarioMatrix.CAPABILITY_EVENT_BUS));
        assertTrue(required.contains(DefaultScenarioMatrix.HUD_TITLE));
        assertTrue(required.contains(DefaultScenarioMatrix.HUD_ACTIONBAR));
        assertTrue(required.contains(DefaultScenarioMatrix.HUD_TOAST));
        assertTrue(required.contains(DefaultScenarioMatrix.HUD_CHAT));
        assertTrue(required.contains(DefaultScenarioMatrix.INTEGRATED_LOOPBACK));
        assertThrows(UnsupportedOperationException.class, () -> required.add("acceptance/extra"));
    }

    @Test
    @DisplayName("未知平台立即拒绝，不能退化成空清单")
    void 未知平台拒绝() {
        assertThrows(IllegalArgumentException.class, () -> DefaultScenarioMatrix.requiredFor("unknown"));
    }
}
