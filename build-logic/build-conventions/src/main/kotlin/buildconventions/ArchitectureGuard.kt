package buildconventions

import org.gradle.api.Project
import java.io.File

/**
 * 架构守卫的核心扫描逻辑（ADR-0028 决策 7）。
 *
 * 只做「读文件 + 判定」，不依赖 Gradle 任务模型，便于单测直接驱动。
 * 所有源码扫描都先经 [SourceMasker] 脱敏，避免注释与字符串造成误报。
 */
object ArchitectureGuard {
    /**
     * 允许出现在 `settings.gradle.kts` include 里的顶层前缀（护栏「顶层目录语义唯一」）。
     *
     * 注意 `mc-shared` 不在其中：它是纯源码树，一旦被 include 成 Gradle 模块，
     * 跨版本编译能力立刻失效（护栏「mc-shared/ 永远是源码树」）。
     */
    val ALLOWED_TOP_LEVEL = listOf("core", "platform", "modules", "examples", "e2e")

    /**
     * 扫描某个受管目录下的全部 Java 源码，检出禁止的外部引用。
     *
     * @param root 仓库根
     * @param managedDir 受管目录（相对根），如 `core` 或 `mc-shared`
     * @param tier 档位
     * @return 违规列表；空表示通过
     */
    fun scanSources(root: File, managedDir: String, tier: ArchitectureTier): List<ArchitectureViolation> {
        val dir = File(root, managedDir)
        val forbidden = tier.forbiddenPrefixes()
        // 目录不存在或该档位不限制任何前缀时无事可做
        if (!dir.isDirectory || forbidden.isEmpty()) {
            return emptyList()
        }
        val violations = mutableListOf<ArchitectureViolation>()
        dir
            .walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .sortedBy { it.path }
            .forEach { file ->
                val masked = SourceMasker.mask(file.readText())
                forbidden.forEach { prefix ->
                    if (masked.contains(prefix)) {
                        violations +=
                            ArchitectureViolation(
                                ArchitectureViolation.Kind.PLATFORM_REFERENCE,
                                relative(root, file),
                                "引用了「$prefix*」（${tier.name} 档禁止）",
                            )
                    }
                }
            }
        return violations
    }

    /**
     * `mc-shared` 形态校验：其下出现 `build.gradle.kts` 即违规。
     *
     * 源码树一旦变成 Gradle 模块，跨版本编译能力立刻失效（ADR-0028 决策 1）。
     *
     * @param root 仓库根
     * @return 违规列表
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
            }.toList()
    }

    /**
     * 顶层语义校验：`settings.gradle.kts` 的每个 include 必须落在允许前缀内。
     *
     * @param root 仓库根
     * @return 违规列表
     */
    fun checkTopLevelSemantics(root: File): List<ArchitectureViolation> {
        val settings = File(root, "settings.gradle.kts")
        if (!settings.isFile) {
            return emptyList()
        }
        val violations = mutableListOf<ArchitectureViolation>()
        INCLUDE_PATTERN.findAll(settings.readText()).forEach { match ->
            // Gradle 允许 include(":a:b") 的绝对路径写法，前导冒号不代表顶层目录，需先剥掉。
            val path = match.groupValues[1].trimStart(':')
            val top = path.substringBefore(':')
            if (top !in ALLOWED_TOP_LEVEL) {
                violations +=
                    ArchitectureViolation(
                        ArchitectureViolation.Kind.TOP_LEVEL_SEMANTICS,
                        "settings.gradle.kts",
                        "include「$path」的顶层前缀「$top」不在允许集合 $ALLOWED_TOP_LEVEL 内",
                    )
            }
        }
        return violations
    }

    /**
     * 孤儿检查：`mc-shared` 下的每个端/区间目录，必须至少被一条活跃 lane 命中。
     *
     * @param root 仓库根
     * @param laneMcVersions 活跃车道的 MC 版本集合
     * @return 违规列表
     */
    fun checkOrphans(root: File, laneMcVersions: Collection<String>): List<ArchitectureViolation> {
        val dir = File(root, "mc-shared")
        if (!dir.isDirectory) {
            return emptyList()
        }
        val violations = mutableListOf<ArchitectureViolation>()
        // 根端目录：只要有车道挂载即不算孤儿
        listOf("common", "client", "server").forEach { side ->
            val sideDir = File(dir, side)
            if (sideDir.isDirectory && sideDir.walkTopDown().none { it.isFile && it.extension == "java" }) {
                violations +=
                    ArchitectureViolation(
                        ArchitectureViolation.Kind.ORPHAN,
                        "mc-shared/$side",
                        "目录存在但没有任何源码——空壳目录不应保留（ADR-0015）",
                    )
            }
        }
        // 区间目录：必须命中至少一个活跃车道版本
        val gensrc = File(dir, "gensrc")
        if (gensrc.isDirectory) {
            gensrc.listFiles().orEmpty().filter { it.isDirectory }.forEach { rangeDir ->
                val hit = laneMcVersions.any { McSharedRanges.matches(rangeDir.name, it) }
                if (!hit) {
                    violations +=
                        ArchitectureViolation(
                            ArchitectureViolation.Kind.ORPHAN,
                            "mc-shared/gensrc/${rangeDir.name}",
                            "区间未命中任何活跃车道（活跃版本：${laneMcVersions.sorted()}）",
                        )
                }
            }
        }
        return violations
    }

    /**
     * 域模块依赖方向校验（ADR-0030 决策 5）。
     *
     * 规则：实现模块之间不得横向依赖——`<域>-client` 不许依赖 `<域>-server`，反之亦然。
     * 两端若需协作，只能经 `-common-api` / `-core` / 各自的 `-internal` 抽象，
     * 或由平台车道在装配处同时持有两侧实现（组合根）。
     *
     * 这是「内部引用走 internal 接口、不触碰实现」的机检落点：没有它，任何人都能给
     * `-client` 加一行 `implementation(project(":…:xxx-server"))` 而编过。
     *
     * @param dependencies 模块路径 → 其直接依赖的模块路径集合（由 Gradle 项目模型提供）
     * @return 违规列表
     */
    fun checkDomainModuleDirections(dependencies: Map<String, Set<String>>): List<ArchitectureViolation> {
        val violations = mutableListOf<ArchitectureViolation>()
        dependencies.forEach { (module, deps) ->
            val moduleDomain = domainOf(module) ?: return@forEach
            deps.forEach { dep ->
                val depDomain = domainOf(dep)
                if (depDomain != null && depDomain != moduleDomain) {
                    // 护栏「域之间零依赖」：跨域协作只能经 EventBus，不得直接依赖另一个域的模块
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

    /** 统计受管目录下的 Java 文件数（供登记校验与报告使用）。 */
    fun javaFileCount(project: Project, managedDir: String): Int {
        val dir = project.rootProject.file(managedDir)
        if (!dir.isDirectory) {
            return 0
        }
        return dir.walkTopDown().count { it.isFile && it.extension == "java" }
    }

    private fun relative(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).removePrefix(File.separator)

    private val INCLUDE_PATTERN = Regex("include\\(\\s*\"([^\"]+)\"\\s*\\)")
}
