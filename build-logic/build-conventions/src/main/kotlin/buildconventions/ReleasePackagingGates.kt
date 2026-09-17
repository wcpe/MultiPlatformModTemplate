package buildconventions

import org.gradle.api.Project
import org.gradle.api.Task
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
 * 一键全量构建：全部平台车道已是根构建子模块（ADR-0026），直接聚合 subprojects 的 build，
 * 并显式执行最终发布产物结构门 + dist 聚合。
 */
private fun registerBuildAllGate(
    project: Project,
    verifyReleasePackaging: TaskProvider<Task>,
    collectReleaseArtifacts: TaskProvider<Task>,
) {
    project.tasks.register("buildAll") {
        group = "build"
        description = "构建全部模块、校验发布产物并聚合到 build/dist/"
        dependsOn(
            project.subprojects
                .filterNot { it.path == ":platform:bukkit" }
                .map { subproject -> subproject.tasks.matching { it.name == "build" } },
        )
        dependsOn(verifyReleasePackaging)
        dependsOn(collectReleaseArtifacts)
    }
}
