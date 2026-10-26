package buildconventions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 版本区间匹配的行为测试（ADR-0028 决策 3）。
 *
 * 这些断言守住「同命名组共享、跨组独立」的正确性：区间判定错了，要么把不兼容的源码挂进车道
 * （编译失败），要么该共享的没共享（静默退回重复副本）。
 */
class McSharedRangesTest {
    @Test
    fun `精确版本只命中自身`() {
        assertTrue(McSharedRanges.matches("1.20.1", "1.20.1"))
        assertFalse(McSharedRanges.matches("1.20.1", "1.20.2"))
        assertFalse(McSharedRanges.matches("1.20.1", "1.21.1"))
    }

    @Test
    fun `短版本按前缀命中同系列`() {
        // 目录名 1.20 应覆盖 1.20.x，但不越到 1.21
        assertTrue(McSharedRanges.matches("1.20", "1.20.1"))
        assertTrue(McSharedRanges.matches("1.20", "1.20.2"))
        assertFalse(McSharedRanges.matches("1.20", "1.21.1"))
    }

    @Test
    fun `闭区间两端都含`() {
        assertTrue(McSharedRanges.matches("1.20-1.21", "1.20.1"))
        assertTrue(McSharedRanges.matches("1.20-1.21", "1.21.1"))
        assertFalse(McSharedRanges.matches("1.20-1.21", "26.2"))
        assertFalse(McSharedRanges.matches("1.20-1.21", "1.19.4"))
    }

    @Test
    fun `下界区间含下界且覆盖更高版本`() {
        assertTrue(McSharedRanges.matches("1.21.5+", "1.21.5"))
        assertTrue(McSharedRanges.matches("1.21.5+", "26.2"))
        assertFalse(McSharedRanges.matches("1.21.5+", "1.21.4"))
    }

    @Test
    fun `多段版本按数值比较而非字符串`() {
        // 字符串比较会把 1.21.10 判到 1.21.9 之前，逐段整数比较则正确
        assertTrue(McSharedRanges.matches("1.21.5+", "1.21.10"))
        assertTrue(McSharedRanges.matches("1.20-1.21", "1.21.10"))
    }

    @Test
    fun `无法解析的版本一律不命中`() {
        assertFalse(McSharedRanges.matches("1.20", ""))
        assertFalse(McSharedRanges.matches("1.20", "abc"))
    }

    @Test
    fun `版本编码只取数字段`() {
        assertEquals(listOf(1, 20, 1), McSharedRanges.encode("1.20.1"))
        // 预发布后缀不参与比较：1.21.1-pre1 仍归属 1.21.1 系列
        assertEquals(listOf(1, 21, 1), McSharedRanges.encode("1.21.1-pre1"))
        assertEquals(emptyList<Int>(), McSharedRanges.encode("abc"))
    }
}
