package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 架构守卫插件（`build-conventions.architecture`，ADR-0028 决策 7）。
 *
 * 只保留结构性约定的机检：`mc-shared` 不得退化成 Gradle 模块、工程路径不得落在未登记的
 * 顶层目录。判据是文件系统事实与 Gradle 自己的工程路径模型，不解析脚本源码文本。
 *
 * 分层与依赖方向的约束不在这里做：那类约束由依赖声明本身（不在 classpath 上就编不过）与
 * ArchUnit（字节码级类图断言，见 `LayeringArchTest` / `DomainArchTest`）承担。细节见
 * [ArchitectureGuard] 的类注释。
 *
 * 插件不绑定具体车道：由根构建 apply 一次，对所有受管目录统一校验。
 */
class ArchitectureGuardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rootDir = project.layout.settingsDirectory.asFile
        // 配置期把守卫需要的一切固化成不可变值：动作只捕获这些值，不捕获 project，
        // 否则配置缓存会因 Project 不可序列化而失败（本仓已开启 configuration-cache）。
        //
        // 数据来源统一为 settings.gradle.kts 下发的普通数据，不在根工程遍历其它工程：
        // - allProjectPaths：每个工程在自身 beforeProject 里登记（含根工程的 ":"）；
        // - domainModuleDependencies：每个域模块在自身 afterEvaluate 里上报直接项目依赖。
        // 根工程配置期读 `rootProject.allprojects` / `subprojects[i].configurations` 在
        // Isolated Projects 下属于跨工程模型访问，且后者因求值顺序会拿到空表。
        val sharedProperties = project.gradle.extensions.extraProperties
        // 清单由各工程在自身 beforeProject 里逐个登记，根工程配置期尚未填满（根先于子配置），
        // 故与 domainDependencies 一样用 provider 延迟到配置末尾求值。
        val projectPaths = project.providers.provider {
            @Suppress("UNCHECKED_CAST")
            (sharedProperties.get("allProjectPaths") as? List<String>)?.sorted().orEmpty()
        }
        // 域依赖表由各域模块在 afterEvaluate 里上报，同样延迟取值。
        val domainDependencies = project.providers.provider {
            @Suppress("UNCHECKED_CAST")
            (sharedProperties.get("domainModuleDependencies") as? Map<String, Set<String>>).orEmpty()
        }

        project.tasks.register("verifyArchitecture") {
            group = "verification"
            description = "架构守卫：mc-shared 形态、顶层语义与依赖方向"
            inputs.property("projectPaths", projectPaths)
            inputs.property("domainDependencies", domainDependencies)
            val resolvedProjectPaths = projectPaths
            val resolvedDomainDependencies = domainDependencies
            doLast {
                val scannedPaths = resolvedProjectPaths.get()
                val domainDeps = resolvedDomainDependencies.get()
                // 空输入会让守卫静默通过——直接失败，避免「门禁存在但恒绿」。
                if (scannedPaths.isEmpty() || domainDeps.isEmpty()) {
                    throw GradleException(
                        "架构守卫输入为空（工程 ${scannedPaths.size} 个 / 域模块 ${domainDeps.size} 个）：" +
                            "settings.gradle.kts 未登记 allProjectPaths 或 domainModuleDependencies",
                    )
                }
                val violations =
                    buildList {
                        addAll(ArchitectureGuard.checkMcSharedShape(rootDir))
                        addAll(ArchitectureGuard.checkTopLevelSemantics(scannedPaths))
                        addAll(ArchitectureGuard.checkDomainModuleDirections(domainDeps))
                    }

                if (violations.isNotEmpty()) {
                    throw GradleException(
                        buildString {
                            appendLine("架构守卫发现 ${violations.size} 处违规：")
                            violations.forEach { appendLine("  " + it.describe()) }
                            append("规则依据 .claude/rules/architecture-invariants.md 与 ADR-0028 决策 7")
                        },
                    )
                }

                logger.lifecycle(
                    "架构守卫通过：mc-shared 形态 / 顶层语义 / 依赖方向" +
                        "（${scannedPaths.size} 个工程，${domainDeps.size} 个域模块）",
                )
            }
        }

        // 根工程不一定应用了 java/lifecycle-base，`check` 可能不存在：
        // 存在则挂进去，不存在就只提供独立任务（由 CI/发布流程显式调用）。
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn("verifyArchitecture") }
    }
}
