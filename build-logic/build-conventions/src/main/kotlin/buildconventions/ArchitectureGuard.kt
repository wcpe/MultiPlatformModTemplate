package buildconventions

import java.io.File

/**
 * 架构守卫的结构性检查（ADR-0028 决策 7）。
 *
 * <h2>为什么这里只剩结构性检查</h2>
 *
 * 分层与依赖方向的约束不在这里做。那类约束已有两个更强的落点：
 *
 * - **依赖声明本身**：某个模块不该看见的东西就不在它的 classpath 上，引用会编译失败。
 *   这是零成本且无法绕过的第一道墙。
 * - **ArchUnit**：见 `core/kernel` 的 `LayeringArchTest`（L0 内核零平台依赖）与各域模块的
 *   `DomainArchTest`（域间不互依、无环）。它在字节码层面建类图再断言规则，比扫源码文本可靠：
 *   不会漏掉通配 import 或多行 import，也不会把注释、字符串里的类名误判为依赖。
 *
 * 本对象只保留那些无法由上面两者表达、且可用结构化事实判定的约定：文件是否存在于不该存在的
 * 位置、工程路径是否落在允许的顶层前缀内。
 *
 * 判据一律取文件系统事实或工程路径这类结构化数据，不解析脚本源码文本。
 */
object ArchitectureGuard {
    /**
     * 允许的顶层工程前缀（护栏「顶层目录语义唯一」）。
     *
     * `mc-shared` 不在其中：它是纯源码树，一旦被 include 成 Gradle 模块，跨版本编译能力
     * 立刻失效（护栏「mc-shared/ 永远是源码树」）。
     */
    val ALLOWED_TOP_LEVEL = listOf("core", "platform", "modules", "examples", "e2e", "build-logic")

    /**
     * `mc-shared` 必须是纯源码树：其下不得出现任何构建脚本。
     *
     * 一旦出现 `build.gradle(.kts)`，它就会（或迟早会）被 include 成 Gradle 模块，跨版本编译
     * 能力随即失效。这条只能靠文件系统事实判定，ArchUnit 与依赖声明都表达不了。
     *
     * @param root 仓库根
     * @return 违规列表；空表示通过
     */
    fun checkMcSharedShape(root: File): List<ArchitectureViolation> {
        val dir = File(root, "mc-shared")

        if (!dir.isDirectory) {
            return emptyList()
        }

        return dir
            .walkTopDown()
            .filter { it.isFile && it.name.startsWith("build.gradle") }
            .sortedBy { it.path }
            .map {
                ArchitectureViolation(
                    ArchitectureViolation.Kind.MC_SHARED_SHAPE,
                    relative(root, it),
                    "mc-shared 是源码树，不得出现构建脚本（否则退化为 Gradle 模块）",
                )
            }
            .toList()
    }

    /**
     * 顶层语义校验：每个已 include 的工程路径必须落在 [ALLOWED_TOP_LEVEL] 之内。
     *
     * 判据是工程路径（`Project.getPath()`，如 `:platform:fabric:26.2`），由 Gradle 自己的模型
     * 给出，不解析 `settings.gradle.kts` 的文本。因此多参数 `include("a", "b")`、动态
     * `include(path)`、换行缩进等写法都不会漏检。
     *
     * @param projectPaths 全部工程路径（含根工程 `:`）
     * @return 违规列表
     */
    fun checkTopLevelSemantics(projectPaths: Collection<String>): List<ArchitectureViolation> {
        return projectPaths
            .asSequence()
            .filter { it != ":" }
            .map { it.removePrefix(":").substringBefore(':') }
            .filter { it.isNotEmpty() && it !in ALLOWED_TOP_LEVEL }
            .distinct()
            .sorted()
            .map { topLevel ->
                ArchitectureViolation(
                    ArchitectureViolation.Kind.TOP_LEVEL_SEMANTICS,
                    topLevel,
                    "顶层目录只允许 ${ALLOWED_TOP_LEVEL.joinToString(" / ")}；" +
                        "新增顶层目录须先立 ADR（护栏「顶层目录语义唯一」）",
                )
            }
            .toList()
    }

    private fun relative(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).removePrefix(File.separator)
}
