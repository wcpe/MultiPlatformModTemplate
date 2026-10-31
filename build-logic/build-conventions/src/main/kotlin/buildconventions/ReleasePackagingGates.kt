package buildconventions

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * 发布产物结构门与全量构建入口（注册在根工程）。
 *
 * - `verifyReleasePackaging`：五平台最终自包含发布产物结构门。全部平台车道均为根构建子模块（ADR-0026），
 *   直接依赖其任务；任务不存在即配置期失败。
 * - `buildFabric262`：Fabric 26.2 车道构建壳；`verifyReleasePackaging` 额外依赖它（整条 `build`，含测试），
 *   比"仅校验产物"更强，属有意加严，搬迁后保持。
 * - `buildAll`：一键全量构建 = 全部子工程 `build` + 结构门 + dist 聚合。
 *
 * 返回 `buildFabric262`，供 26.2 交付门复用同一任务提供者。
 */
internal fun registerReleasePackagingGates(
    project: Project,
    collectReleaseArtifacts: TaskProvider<Task>,
): TaskProvider<Task> {
    val verifyReleasePackaging = registerReleasePackagingGate(project)
    val buildFabric262 = registerFabric262LaneShell(project, verifyReleasePackaging)
    registerBuildAllGate(project, verifyReleasePackaging, collectReleaseArtifacts)
    return buildFabric262
}

private fun registerReleasePackagingGate(project: Project): TaskProvider<Task> =
    project.tasks.register("verifyReleasePackaging") {
        group = "verification"
        description = "校验五平台最终自包含发布产物（全部平台车道为根构建子模块）"
        // Bukkit 已拆为每版本子工程；聚合任务在 platform-bukkit 壳上
        dependsOn(":platform:bukkit:verifyPackaging")
        dependsOn(
            ":platform:fabric:fabric-1.20.1:verifyPackaging",
            ":platform:fabric:fabric-1.21.1:verifyPackaging",
            ":platform:fabric:fabric-26.2:verifyPackaging",
            ":platform:forge:forge-1.20.1:verifyPackaging",
            ":platform:forge:forge-1.21.1:verifyPackaging",
            ":platform:forge:forge-26.2:verifyPackaging",
            ":platform:neoforge:neoforge-1.20.2:verifyPackaging",
            ":platform:sponge:sponge-1.20.1:verifyPackaging",
        )
    }

private fun registerFabric262LaneShell(
    project: Project,
    verifyReleasePackaging: TaskProvider<Task>,
): TaskProvider<Task> {
    val buildFabric262 =
        project.tasks.register("buildFabric262") {
            group = "build"
            description = "构建 Fabric 26.2 车道（根子模块）"
            dependsOn(":platform:fabric:fabric-26.2:build")
        }
    verifyReleasePackaging.configure {
        dependsOn(buildFabric262)
    }
    return buildFabric262
}

/**
 * 一键全量构建：全部平台车道已是根构建子模块（ADR-0026），聚合各工程的 `build`，
 * 并显式执行最终发布产物结构门 + dist 聚合。
 *
 * 不用 `project.subprojects.map { it.tasks.matching { ... } }`——那是配置期读取其它工程的
 * 任务容器，Isolated Projects 会拒绝（"cannot access 'Project.tasks' functionality"）。
 * 改为从 settings 下发的工程清单拼任务路径：协调逻辑仍集中在根，且不触碰别的工程模型。
 */
private fun registerBuildAllGate(
    project: Project,
    verifyReleasePackaging: TaskProvider<Task>,
    collectReleaseArtifacts: TaskProvider<Task>,
) {
    project.tasks.register("buildAll") {
        group = "build"
        description = "构建全部模块、校验发布产物并聚合到 build/dist/"
        dependsOn(allModuleBuildPaths(project))
        dependsOn(verifyReleasePackaging)
        dependsOn(collectReleaseArtifacts)
        // 架构守卫（ADR-0028 决策 7）：全量构建必须过守卫，否则规则会因无人调用而形同虚设。
        dependsOn("verifyArchitecture")
    }
}

/**
 * 全部可构建子工程的 `:path:build` 任务路径。
 *
 * 清单来源：settings.gradle.kts 在 `beforeProject` / `afterEvaluate` 里逐个登记。
 * 根工程先于子工程配置，注册任务时集合尚未填满，故用 `provider { }` 延迟到配置末尾求值
 *（此时所有工程的 afterEvaluate 都已执行）。集合本身是 settings 下发的普通数据，
 * 读取它不构成跨工程模型访问。
 *
 * 只取登记为"确实有 build 任务"的工程：聚合壳（`:core` / `:platform:bukkit` 等只有一行注释、
 * 未应用 base/java 插件）没有 build 任务。该判定由各工程在自身 afterEvaluate 里完成，
 * 与迁移前根侧 `subproject.tasks.matching { it.name == "build" }` 的过滤语义一致。
 * 另外仍显式排除 `:platform:bukkit`——它是聚合壳，其 build 不作数。
 */
private fun allModuleBuildPaths(project: Project): Provider<List<String>> {
    @Suppress("UNCHECKED_CAST")
    val withBuild =
        project.gradle.extensions.extraProperties.get("projectsWithBuildTask") as? MutableSet<String>
            ?: error("settings.gradle.kts 未下发 projectsWithBuildTask：无法装配 buildAll")
    return project.provider {
        withBuild
            .filterNot { it == ":" || it == ":platform:bukkit" }
            .sorted()
            .map { "$it:build" }
    }
}
