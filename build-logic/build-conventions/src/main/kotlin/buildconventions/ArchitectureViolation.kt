package buildconventions

/**
 * 架构守卫的一条违规（ADR-0028 决策 7）。
 *
 * @property kind 违规类别
 * @property where 定位（相对仓库根的文件路径或构建脚本标识）
 * @property detail 具体说明（命中的前缀、未登记的目录名等）
 */
data class ArchitectureViolation(
    val kind: Kind,
    val where: String,
    val detail: String,
) {
    /** 违规类别。 */
    enum class Kind {
        /** 顶层语义：工程路径的顶层前缀不在允许集合内。 */
        TOP_LEVEL_SEMANTICS,

        /** mc-shared 形态：出现 build.gradle.kts。 */
        MC_SHARED_SHAPE,
    }

    /** 人类可读单行描述。 */
    fun describe(): String = "[${kind.name}] $where —— $detail"
}
