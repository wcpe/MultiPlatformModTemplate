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
        val rootDir = project.rootProject.layout.projectDirectory.asFile
        // 配置期把守卫需要的一切固化成不可变值：动作只捕获这些值，不捕获 project，
        // 否则配置缓存会因 Project 不可序列化而失败（本仓已开启 configuration-cache）。
        // 工程路径来自 settings 期即已确定的静态事实，不依赖子工程求值。
        val projectPaths: List<String> = project.rootProject.allprojects.map { it.path }.sorted()

        project.tasks.register("verifyArchitecture") {
            group = "verification"
            description = "架构守卫：mc-shared 形态与顶层语义"
            doLast {
                val violations =
                    buildList {
                        addAll(ArchitectureGuard.checkMcSharedShape(rootDir))
                        addAll(ArchitectureGuard.checkTopLevelSemantics(projectPaths))
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

                // 带上被扫描的工程数：守卫一旦退化成「零输入」就会静默通过，这个数字是可观测的反向信号。
                logger.lifecycle("架构守卫通过：mc-shared 形态 / 顶层语义（${projectPaths.size} 个工程）")
            }
        }

        // 根工程不一定应用了 java/lifecycle-base，`check` 可能不存在：
        // 存在则挂进去，不存在就只提供独立任务（由 CI/发布流程显式调用）。
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn("verifyArchitecture") }
    }
}
