package top.wcpe.mc.mpmt.acceptance.gametest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 上下文断言 / 跳过默认方法（ADR-0014）。 */
class ServerGameTestContextTest {

    private final ServerGameTestContext ctx = new FakeServerGameTestContext();

    @Test
    @DisplayName("onMain 返回主线程执行结果")
    void onMain返回值() {
        assertEquals(42, ctx.onMain(() -> 42));
    }

    @Test
    @DisplayName("assertTrue：真不抛、假抛断言错")
    void 断言真假() {
        assertDoesNotThrow(() -> ctx.assertTrue(true, "应不抛"));
        ServerGameTestAssertionError error =
                assertThrows(ServerGameTestAssertionError.class, () -> ctx.assertTrue(false, "条件不成立"));
        assertEquals("条件不成立", error.getMessage());
    }

    @Test
    @DisplayName("assertEquals：相等不抛、不等抛含期望/实际")
    void 断言相等() {
        assertDoesNotThrow(() -> ctx.assertEquals("a", "a", "应相等"));
        ServerGameTestAssertionError error =
                assertThrows(ServerGameTestAssertionError.class, () -> ctx.assertEquals(1, 2, "不相等"));
        assertTrue(error.getMessage().contains("期望=1"), error.getMessage());
        assertTrue(error.getMessage().contains("实际=2"), error.getMessage());
    }

    @Test
    @DisplayName("fail 抛断言错、skip 抛跳过")
    void 失败与跳过() {
        assertThrows(ServerGameTestAssertionError.class, () -> ctx.fail("失败"));
        ServerGameTestSkipException skip =
                assertThrows(ServerGameTestSkipException.class, () -> ctx.skip("未装配"));
        assertEquals("未装配", skip.getMessage());
    }
}
