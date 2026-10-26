package buildconventions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SourceMasker 行为断言（ADR-0028 决策 7）：脱敏必须等长、保留换行，且只抹掉注释与字面量内容。
 *
 * <p>这些断言锁定「脱敏后长度与换行位置不变」这一前提——架构守卫靠行号定位违规，
 * 长度或换行一旦漂移，报告里的位置就不可信。
 */
class SourceMaskerTest {
    @Test
    fun `块注释内容被抹掉但长度与换行不变`() {
        val source = "a /* net.fabricmc.X */ b"
        val masked = SourceMasker.mask(source)
        assertEquals(source.length, masked.length)
        assertTrue(!masked.contains("net.fabricmc"))
        assertTrue(masked.startsWith("a "))
        assertTrue(masked.endsWith(" b"))
    }

    @Test
    fun `行注释抹到行尾且保留换行`() {
        val source = "a // net.fabricmc.X\nb"
        val masked = SourceMasker.mask(source)
        assertEquals(source.length, masked.length)
        assertEquals(1, masked.count { it == '\n' })
        assertTrue(!masked.contains("net.fabricmc"))
        assertTrue(masked.endsWith("\nb"))
    }

    @Test
    fun `普通字符串内容被抹掉`() {
        val source = "String s = \"net.fabricmc.X\";"
        val masked = SourceMasker.mask(source)
        assertEquals(source.length, masked.length)
        assertTrue(!masked.contains("net.fabricmc"))
        // 字面量整体（含引号）替换为空格，故脱敏后只剩声明部分
        assertTrue(masked.startsWith("String s = "))
        assertTrue(masked.endsWith(";"))
    }

    @Test
    fun `转义引号不提前结束字面量`() {
        val source = "String s = \"a\\\"b\"; net.fabricmc.X"
        val masked = SourceMasker.mask(source)
        assertEquals(source.length, masked.length)
        // 转义引号被正确跳过，字符串在第二个引号后结束，故末尾的真实代码保留
        assertTrue(masked.contains("net.fabricmc"))
        assertTrue(!masked.contains("a\\\"b"))
    }

    @Test
    fun `文本块内容被抹掉`() {
        val source = "String t = \"\"\"net.fabricmc.X\"\"\"; net.fabricmc.Y"
        val masked = SourceMasker.mask(source)
        assertEquals(source.length, masked.length)
        assertTrue(!masked.contains("net.fabricmc.X"))
        assertTrue(masked.contains("net.fabricmc.Y"))
    }

    @Test
    fun `未闭合字面量抹到结尾且不越界`() {
        val source = "unclosed \"net.fabricmc.X"
        val masked = SourceMasker.mask(source)
        assertEquals(source.length, masked.length)
        assertTrue(!masked.contains("net.fabricmc"))
    }

    @Test
    fun `未闭合块注释抹到结尾且不越界`() {
        val source = "/* unclosed net.fabricmc.X"
        val masked = SourceMasker.mask(source)
        assertEquals(source.length, masked.length)
        assertTrue(!masked.contains("net.fabricmc"))
    }
}
