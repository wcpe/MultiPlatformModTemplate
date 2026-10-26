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
        /** 源码 import 了禁止的外部包。 */
        PLATFORM_REFERENCE,

        /** 包名 / 类名 / 反射字符串里出现平台标识。 */
        PLATFORM_NAMING,

        /** 构建脚本声明了禁止的依赖。 */
        BUILD_DEPENDENCY,

        /** 受管目录未登记（防改名躲避守卫）。 */
        UNREGISTERED,

        /** 孤儿：mc-shared 目录未被任何活跃 lane 命中。 */
        ORPHAN,

        /** 顶层语义：include 前缀不在允许集合内。 */
        TOP_LEVEL_SEMANTICS,

        /** mc-shared 形态：出现 build.gradle.kts。 */
        MC_SHARED_SHAPE,

        /** 域模块依赖方向：实现模块横向依赖对端实现（ADR-0030 决策 5）。 */
        DEPENDENCY_DIRECTION,
    }

    /** 人类可读单行描述。 */
    fun describe(): String = "[${kind.name}] $where —— $detail"
}
