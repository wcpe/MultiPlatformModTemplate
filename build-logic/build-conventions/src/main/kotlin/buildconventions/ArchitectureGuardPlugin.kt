package buildconventions

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 架构守卫插件（`build-conventions.architecture`，ADR-0028 决策 7）。
 *
 * 把 `.claude/rules/architecture-invariants.md` 的规则变成可失败的门禁：
 * - 三档扫描（[ArchitectureTier]）：`core` 下禁 MC / 加载器 / 平台 SDK，`mc-shared` 下禁加载器与平台 SDK，
 *   `platform` 全允许
 * - [SourceMasker] 脱敏，避免注释与字符串误报
 * - `mc-shared` 形态校验（不得出现构建脚本）
 * - 顶层语义校验（include 前缀白名单）
 * - 孤儿检查（空壳端目录、未命中任何活跃车道的区间目录）
 *
 * 插件本身不绑定具体车道：由根构建 `apply` 一次，对所有受管目录统一校验。
 */
class ArchitectureGuardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val root = project.rootProject.layout.projectDirectory
        // 配置期把守卫需要的一切都固化成不可变值：动作只捕获这些值，不捕获 project，
        // 否则配置缓存会因 Project 不可序列化而失败（本仓已开启 configuration-cache）。
        val laneVersions = ArchitectureGuardSupport.declaredLaneVersions(project)
        val domainDependencies = ArchitectureGuardSupport.domainModuleDependencies(project)
        val rootDir = root.asFile
        project.tasks.register("verifyArchitecture") {
            group = "verification"
            description = "架构守卫：分层引用、mc-shared 形态、顶层语义、孤儿目录与域依赖方向"
            doLast {
                val violations =
                    buildList {
                        addAll(ArchitectureGuard.scanSources(rootDir, "core", ArchitectureTier.STRICT))
                        addAll(ArchitectureGuard.scanSources(rootDir, "mc-shared", ArchitectureTier.MC_AWARE))
                        addAll(ArchitectureGuard.checkMcSharedShape(rootDir))
                        addAll(ArchitectureGuard.checkTopLevelSemantics(rootDir))
                        addAll(ArchitectureGuard.checkOrphans(rootDir, laneVersions))
                        addAll(ArchitectureGuard.checkDomainModuleDirections(domainDependencies))
                    }
                if (violations.isNotEmpty()) {
                    throw org.gradle.api.GradleException(
                        buildString {
                            appendLine("架构守卫发现 ${violations.size} 处违规：")
                            violations.forEach { appendLine("  " + it.describe()) }
                            append("规则依据 .claude/rules/architecture-invariants.md 与 ADR-0028 决策 7")
                        },
                    )
                }
                logger.lifecycle("架构守卫通过：core 严格档 / mc-shared 感知档 / 顶层语义 / 孤儿检查")
            }
        }
        // 根工程不一定应用了 java/lifecycle-base，`check` 可能不存在：
        // 存在则挂进去，不存在就只提供独立任务（由 CI/发布流程显式调用）。
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn("verifyArchitecture") }
    }
}

/**
 * 车道版本来源：从 Gradle 属性 `mpmt.laneMcVersions` 读取（逗号分隔）。
 *
 * 放在属性而非硬编码，是为了让「新增车道」与「守卫认知」保持单一真源；
 * 缺失时回退到当前锁定的 5 个版本（ADR-0028 决策 8），并允许守卫继续工作。
 */
object ArchitectureGuardSupport {
    private const val PROPERTY = "mpmt.laneMcVersions"
    private val FALLBACK = listOf("1.12.2", "1.20.1", "1.20.2", "1.21.1", "26.2")

    /**
     * 读取活跃车道 MC 版本集合。
     *
     * @param project 任意工程（取根属性）
     * @return 版本列表
     */
    fun declaredLaneVersions(project: Project): List<String> {
        val raw =
            project.rootProject
                .findProperty(PROPERTY)
                ?.toString()
                .orEmpty()
        val parsed = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parsed.ifEmpty { FALLBACK }
    }

    /**
     * 收集域模块的直接依赖关系（模块路径 → 依赖的模块路径集合）。
     *
     * 走 Gradle 项目模型而非解析构建脚本文本：模型是结构化事实，文本解析会因写法差异漏判。
     *
     * @param project 任意工程（取 rootProject 的子项目）
     * @return 依赖关系表；非域模块不出现在键里
     */
    fun domainModuleDependencies(project: Project): Map<String, Set<String>> {
        val domainPrefix = ":core:domains:"
        return project.rootProject.subprojects
            .filter { it.path.startsWith(domainPrefix) && it.buildFile.isFile }
            .associate { sub ->
                val deps =
                    sub.configurations
                        .flatMap { configuration ->
                            configuration.dependencies.withType(org.gradle.api.artifacts.ProjectDependency::class.java)
                        }.map { it.path }
                        .toSet()
                sub.path to deps
            }
    }
}
