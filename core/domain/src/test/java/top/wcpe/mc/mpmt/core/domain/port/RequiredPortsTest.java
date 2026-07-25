package top.wcpe.mc.mpmt.core.domain.port;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/** PRD FR-01 必需端口存在性回归测试。 */
class RequiredPortsTest {

    @Test
    void 玩家与世界端口必须存在() {
        assertDoesNotThrow(() -> Class.forName("top.wcpe.mc.mpmt.core.domain.port.PlayerPort"));
        assertDoesNotThrow(() -> Class.forName("top.wcpe.mc.mpmt.core.domain.port.WorldPort"));
    }
}
