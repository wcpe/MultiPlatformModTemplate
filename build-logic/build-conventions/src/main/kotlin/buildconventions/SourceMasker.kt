package buildconventions

/**
 * 源码脱敏器（ADR-0028 决策 7）：扫描前剥掉注释与字符串字面量。
 *
 * 为什么要剥：架构守卫靠「是否出现 `net.fabricmc.` 之类前缀」判定违规，而注释里解释
 * "本模块禁止 import net.fabricmc.*"、测试样例里写一段错误示范，都会被误判成违规。
 * 先脱敏再扫描，让判定只落在真实代码引用上。
 *
 * 处理顺序：块注释 → 行注释 → 字符串 / 字符字面量。保留换行，使行号仍可定位。
 */
object SourceMasker {
    /**
     * 把注释与字符串内容替换为空格（保留换行与长度）。
     *
     * @param source 原始源码
     * @return 等长脱敏文本；换行位置与原文一致
     */
    fun mask(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            // 每个分支负责一种字面量或注释；命中后返回新的扫描位置，未命中则原样复制一个字符
            val next =
                when {
                    isBlockCommentStart(source, i) -> maskBlockComment(source, i, out)
                    isLineCommentStart(source, i) -> maskLineComment(source, i, out)
                    isTextBlockStart(source, c, i) -> maskTextBlock(source, i, out)
                    c == '"' || c == '\'' -> maskQuotedLiteral(source, i, c, out)
                    else -> {
                        out.append(c)
                        i + 1
                    }
                }
            i = next
        }
        return out.toString()
    }

    // 是否块注释起点（斜杠加星号）
    private fun isBlockCommentStart(source: String, i: Int): Boolean =
        source[i] == '/' && i + 1 < source.length && source[i + 1] == '*'

    // 是否行注释起点（两个斜杠）
    private fun isLineCommentStart(source: String, i: Int): Boolean =
        source[i] == '/' && i + 1 < source.length && source[i + 1] == '/'

    // 是否文本块起点（三个双引号，Java 15+）
    private fun isTextBlockStart(source: String, c: Char, i: Int): Boolean =
        c == '"' && source.startsWith("\"\"\"", i)

    // 抹掉块注释，返回注释结束后的位置
    private fun maskBlockComment(source: String, from: Int, out: StringBuilder): Int {
        val end = source.indexOf("*/", from + 2)
        val stop = if (end < 0) source.length else end + 2
        blank(source, from, stop, out)
        return stop
    }

    /** 抹掉行注释（不含换行符），返回行尾位置。 */
    private fun maskLineComment(source: String, from: Int, out: StringBuilder): Int {
        val end = source.indexOf('\n', from)
        val stop = if (end < 0) source.length else end
        blank(source, from, stop, out)
        return stop
    }

    /** 抹掉文本块，返回结束三引号之后的位置。 */
    private fun maskTextBlock(source: String, from: Int, out: StringBuilder): Int {
        val end = source.indexOf("\"\"\"", from + 3)
        val stop = if (end < 0) source.length else end + 3
        blank(source, from, stop, out)
        return stop
    }

    /** 找到普通字符串 / 字符字面量的结束位置（处理反斜杠转义），到换行即止。 */
    private fun quotedLiteralEnd(source: String, from: Int, quote: Char): Int {
        var j = from + 1
        while (j < source.length) {
            val d = source[j]
            if (d == '\\') {
                j += 2
            } else if (d == quote || d == '\n') {
                return j + 1
            } else {
                j++
            }
        }
        return j
    }

    /** 抹掉引号字面量，返回字面量之后的位置。 */
    private fun maskQuotedLiteral(
        source: String,
        from: Int,
        quote: Char,
        out: StringBuilder,
    ): Int {
        val stop = minOf(quotedLiteralEnd(source, from, quote), source.length)
        blank(source, from, stop, out)
        return stop
    }

    /** 把 [from, to) 区间替换为空格，换行原样保留。 */
    private fun blank(source: String, from: Int, to: Int, out: StringBuilder) {
        for (k in from until to) {
            out.append(if (source[k] == '\n') '\n' else ' ')
        }
    }
}
