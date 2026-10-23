package buildconventions

/**
 * `mc-shared/gensrc/<range>/` 的版本区间语法（ADR-0028 决策 3）。
 *
 * 目录名即区间声明，只有**命中区间**的车道才会挂载该目录。语法：
 * ```
 * 1.20           精确版本（等价于 1.20~1.20.x）
 * 1.20-1.21.4   闭区间
 * 1.21.5+       下界（含）
 * ```
 * 比较按**整数编码**逐段进行，避免字符串比较把 `1.21.10` 判到 `1.21.9` 之前。
 *
 * 为什么需要它：同一段逻辑在不同 MC 命名组下成员名会变（`mcp ≤1.12.2` /
 * `mojmap 1.20.1~1.21.1` / `unobfuscated 26.2`），一份文本通吃会直接编译失败。
 * 区间目录让"同组共享、跨组独立"成为机制而不是约定。
 */
object McSharedRanges {

    /**
     * 把 MC 版本字符串编码成可比较的整数序列。
     *
     * 只取数字段：`1.20.1` → `[1, 20, 1]`；遇到非数字段停止（如 `1.20.1-pre1` 取 `[1, 20, 1]`）。
     *
     * @param version MC 版本字符串
     * @return 数字段列表；无法解析时返回空列表
     */
    fun encode(version: String): List<Int> =
        version
            .trim()
            .split('.')
            .map { segment -> segment.takeWhile { it.isDigit() } }
            .takeWhile { it.isNotEmpty() }
            .map { it.toInt() }

    /**
     * 判断某 MC 版本是否落在给定区间声明内。
     *
     * @param range 区间声明（目录名），如 `1.20`、`1.20-1.21.4`、`1.21.5+`
     * @param mcVersion 车道实际 MC 版本
     * @return 是否命中
     */
    fun matches(range: String, mcVersion: String): Boolean {
        val actual = encode(mcVersion)
        if (actual.isEmpty()) {
            return false
        }
        val spec = range.trim()
        return when {
            spec.endsWith("+") -> compare(actual, encode(spec.dropLast(1))) >= 0
            spec.contains('-') -> {
                val parts = spec.split('-', limit = 2)
                compare(actual, encode(parts[0])) >= 0 && compare(actual, encode(parts[1]), prefixAware = true) <= 0
            }
            else -> compare(actual, encode(spec), prefixAware = true) == 0
        }
    }

    /**
     * 逐段比较两个版本编码。
     *
     * @param a 左值
     * @param b 右值
     * @param prefixAware 为 true 时，缺省段视为通配：`[1,20]` 能匹配 `[1,20,1]`
     * @return 负数 / 0 / 正数
     */
    private fun compare(a: List<Int>, b: List<Int>, prefixAware: Boolean = false): Int {
        if (b.isEmpty()) {
            return 1
        }
        if (prefixAware) {
            // 前缀语义：只比较 b 覆盖到的段，b 的每个段都必须与 a 相等
            if (a.size < b.size) {
                return -1
            }
            for (i in b.indices) {
                if (a[i] != b[i]) {
                    return a[i].compareTo(b[i])
                }
            }
            return 0
        }
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) {
                return left.compareTo(right)
            }
        }
        return 0
    }
}
