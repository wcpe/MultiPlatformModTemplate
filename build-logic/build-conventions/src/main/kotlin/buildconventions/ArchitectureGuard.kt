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

    /**
     * 依赖方向校验：域之间不得互相依赖；实现模块不得横向依赖对端实现。
     *
     * <h2>为什么这条必须留在守卫里</h2>
     *
     * 它无法由 ArchUnit 表达：`examples/counter/client` 与 `examples/counter/server` 的类处在
     * **同一个包** `top.wcpe.mc.mpmt.examples.counter`（core 的 capacity 域同样如此），而
     * `slices()` 按**包名**切片，同包不同模块切不开——ArchUnit 只能看到「一个包」，看不到
     * 「两个 Gradle 模块」。依赖声明本身也不拦：`implementation(project(":…:xxx-server"))`
     * 加进 `-client` 一样编得过。
     *
     * 因此判据只能是 **Gradle 项目模型里的模块级直接依赖**（ADR-0030 决策 6 的红线），
     * 数据由 settings.gradle.kts 让各域模块在自身 afterEvaluate 里上报
     *（见 `domainModuleDependencies`）。这是结构化事实，不解析任何脚本文本。
     *
     * 两条规则：
     * - 跨域：`core/domains/<域A>` 不得依赖 `core/domains/<域B>`（`examples/<域>` 同理），
     *   跨域协作只经 EventBus（ADR-0011）；
     * - 同域内：`-client` 不得依赖 `-server`，反之亦然；跨端协作经 common-api / core /
     *   各端 internal 抽象，或由车道在装配处组合。
     *
     * 抽象模块（`-api` / `-internal` / `core` / `common-api`）不受方向限制。
     *
     * @param dependencies 模块路径 → 其直接依赖的模块路径集合
     * @return 违规列表
     */
    fun checkDomainModuleDirections(dependencies: Map<String, Set<String>>): List<ArchitectureViolation> {
        val violations = mutableListOf<ArchitectureViolation>()
        dependencies.forEach { (module, deps) ->
            val moduleDomain = domainOf(module) ?: return@forEach
            deps.forEach { dep ->
                val depDomain = domainOf(dep)
                if (depDomain != null && depDomain != moduleDomain) {
                    violations +=
                        ArchitectureViolation(
                            ArchitectureViolation.Kind.DEPENDENCY_DIRECTION,
                            module,
                            "域之间不得互相依赖：它依赖了另一个域「$dep」；" +
                                "跨域协作应经 EventBus（ADR-0011）",
                        )
                    return@forEach
                }
                // 只比较同域内的模块；域外依赖（如 :core:kernel）不属本规则管辖。
                if (depDomain != moduleDomain) {
                    return@forEach
                }
                val side = sideOf(module) ?: return@forEach
                val depSide = sideOf(dep) ?: return@forEach
                if (side != depSide) {
                    violations +=
                        ArchitectureViolation(
                            ArchitectureViolation.Kind.DEPENDENCY_DIRECTION,
                            module,
                            "实现模块不得依赖对端实现：它依赖了「$dep」（对端 $depSide）；" +
                                "跨端协作应经 common-api / core / internal 抽象，或由车道在装配处组合",
                        )
                }
            }
        }
        return violations
    }

    /**
     * 取模块所属域的路径（去掉末段模块名）。
     *
     * 只认功能域：`core/domains/<域>` 与 `examples/<域>`。
     */
    private fun domainOf(modulePath: String): String? =
        when {
            modulePath.contains(":domains:") -> modulePath.substringBeforeLast(':')
            modulePath.startsWith(":examples:") -> modulePath.substringBeforeLast(':')
            else -> null
        }

    /**
     * 从模块路径识别它的端实现身份。
     *
     * 模块名已短化（`client` / `server`，不再是 `<域>-client`），故按末段精确匹配；
     * 抽象模块（`*-api` / `*-internal` / `core` / `common-api`）返回 null，不受方向限制。
     */
    private fun sideOf(modulePath: String): String? =
        when (modulePath.substringAfterLast(':')) {
            "client" -> "client"
            "server" -> "server"
            else -> null
        }

    private fun relative(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).removePrefix(File.separator)
}
