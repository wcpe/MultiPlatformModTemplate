package top.wcpe.mc.mpmt.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 版本协商边界测试（ADR-0006 / FR-04 验收）。 */
class ProtocolVersionTest {

    @Test
    @DisplayName("兼容性边界：低于下限拒绝、区间内接受、高于上限拒绝")
    void 兼容性边界() {
        assertFalse(ProtocolVersion.isCompatible(ProtocolVersion.MIN_SUPPORTED - 1), "低于最低支持版本应不兼容");
        assertTrue(ProtocolVersion.isCompatible(ProtocolVersion.MIN_SUPPORTED), "等于最低支持版本应兼容");
        assertTrue(ProtocolVersion.isCompatible(ProtocolVersion.CURRENT), "等于当前版本应兼容");
        assertFalse(ProtocolVersion.isCompatible(ProtocolVersion.CURRENT + 1), "高于当前版本（本端不认识）应不兼容");
    }
}
