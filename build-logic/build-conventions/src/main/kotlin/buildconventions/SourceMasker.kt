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
            when {
                // 块注释：整段抹掉，保留内部换行
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    val end = source.indexOf("*/", i + 2)
                    val stop = if (end < 0) source.length else end + 2
                    blank(source, i, stop, out)
                    i = stop
                }
                // 行注释：抹到行尾
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    val end = source.indexOf('\n', i)
                    val stop = if (end < 0) source.length else end
                    blank(source, i, stop, out)
                    i = stop
                }
                // 文本块（Java 15+）：抹到结束的 """
                c == '"' && source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3)
                    val stop = if (end < 0) source.length else end + 3
                    blank(source, i, stop, out)
                    i = stop
                }
                // 普通字符串 / 字符字面量：抹到对应引号（处理转义）
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < source.length) {
                        val d = source[j]
                        if (d == '\\') {
                            j += 2
                            continue
                        }
                        if (d == c || d == '\n') {
                            j++
                            break
                        }
                        j++
                    }
                    blank(source, i, minOf(j, source.length), out)
                    i = minOf(j, source.length)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 把 [from, to) 区间替换为空格，换行原样保留。 */
    private fun blank(source: String, from: Int, to: Int, out: StringBuilder) {
        for (k in from until to) {
            out.append(if (source[k] == '\n') '\n' else ' ')
        }
    }
}
