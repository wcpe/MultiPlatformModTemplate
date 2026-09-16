package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

/**
 * 26.2 三车道交付门（注册在根工程）：构建 Paper / Fabric / Forge 26.2 产物并校验当前权威真服报告。
 *
 * 轮次 / 制品哈希 / 三场景的严格校验强度由 `verifyRealServerReportsStrict`
 *（realserver-report-gate 插件，根 `plugins {}` 中先于本插件应用）承担；
 * 此处只做产物构建门、依赖接线与 `mustRunAfter` 顺序约束。
 */
internal fun registerRealServer262Gates(
    project: Project,
    version: String,
    buildFabric262: TaskProvider<Task>,
) {
    registerBuildRealServerArtifacts262(project, version, buildFabric262)
    wireStrictReportGateOrdering(project)
    registerRealServerAcceptance262(project)
    registerRealServerGate262(project)
}

private fun registerBuildRealServerArtifacts262(
    project: Project,
    version: String,
    buildFabric262: TaskProvider<Task>,
) {
    project.tasks.register("buildRealServerArtifacts262") {
        group = "verification"
        description = "26.2 三车道构建门：Paper 26.2、Fabric 26.2 与 Forge 26.2 产物"
        dependsOn(
            ":platform:bukkit:26.2:verifyPackaging",
            buildFabric262,
            ":platform:forge:forge-26.2:packageArtifacts",
        )
        doLast {
            val forgeProduct =
                project.project(":platform:forge:forge-26.2").layout.buildDirectory
                    .file("libs/mpmt-forge-26.2-$version.jar").get().asFile
            val forgeAcceptance =
                project.project(":platform:forge:forge-26.2").layout.buildDirectory
                    .file("libs/mpmt-forge-acceptance-26.2-$version.jar").get().asFile
            if (!forgeProduct.isFile || !forgeAcceptance.isFile) {
                throw GradleException(
                    "缺少 Forge 26.2 产品或验收产物（已于本次依赖 :platform:forge:forge-26.2:packageArtifacts，仍缺失说明该任务未产出预期命名）：" +
                        "${forgeProduct.absolutePath} / ${forgeAcceptance.absolutePath}",
                )
            }
        }
    }
}

private fun wireStrictReportGateOrdering(project: Project) {
    project.tasks.named("verifyRealServerReportsStrict") {
        mustRunAfter(
            "runRealServerAcceptanceBukkit262",
            "runRealServerAcceptanceFabric262",
            "runRealServerAcceptanceForge262",
        )
    }
}

private fun registerRealServerAcceptance262(project: Project) {
    project.tasks.register("runRealServerAcceptance262") {
        group = "verification"
        description = "26.2 真服门：Paper、Fabric、Forge 26.2 三车道当前报告全通过"
        dependsOn(
            "runRealServerAcceptanceBukkit262",
            "runRealServerAcceptanceFabric262",
            "runRealServerAcceptanceForge262",
            "verifyRealServerReportsStrict",
        )
    }
}

private fun registerRealServerGate262(project: Project) {
    project.tasks.register("runRealServerGate262") {
        group = "verification"
        description = "26.2 交付门：构建三车道并校验当前真服报告"
        dependsOn("buildRealServerArtifacts262", "runRealServerAcceptance262")
    }
}
