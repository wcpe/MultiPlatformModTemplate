package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import java.io.File

/** 一条发布制品：来源 jar、归属 loader 目录与 dist 内的目标名。 */
private data class ReleaseArtifact(val source: File, val loader: String, val targetName: String)

/**
 * 聚合各平台权威可发布 jar 到 `build/dist/{bukkit,fabric,forge,neoforge,sponge}/`（注册在根工程）。
 *
 * <p>不复制 acceptance / plain / dev-shadow / corelib。全部平台车道为根构建子模块（ADR-0026），
 * 产物来源统一经 `project(...)` 的 buildDirectory 解析，并由任务依赖保证已构建；缺失即失败。
 */
internal fun registerReleaseArtifactAggregation(project: Project, version: String): TaskProvider<Task> =
    project.tasks.register("collectReleaseArtifacts") {
        group = "build"
        description =
            "聚合权威可发布 jar 到 build/dist/{bukkit,fabric,forge,neoforge,sponge}/"
        dependsOn("verifyReleasePackaging")
        dependsOn(
            ":platform:bukkit:1.12.2:shadowJar",
            ":platform:bukkit:1.20.1:shadowJar",
            ":platform:bukkit:1.21.1:shadowJar",
            ":platform:bukkit:26.2:shadowJar",
            ":platform:fabric:fabric-1.20.1:remapJar",
            ":platform:fabric:fabric-1.21.1:remapJar",
            ":platform:fabric:fabric-26.2:shadowJar",
            ":platform:forge:forge-1.12.2:reobfJar",
            ":platform:forge:forge-1.20.1:reobfShadowJar",
            ":platform:forge:forge-1.21.1:packageArtifacts",
            ":platform:forge:forge-26.2:packageArtifacts",
            ":platform:neoforge:neoforge-1.20.2:packageArtifacts",
            ":platform:sponge:sponge-1.20.1:shadowJar",
        )

        val distRoot = project.layout.buildDirectory.dir("dist")
        outputs.dir(distRoot)

        doLast {
            copyReleaseArtifacts(project, version, distRoot.get().asFile)
        }
    }

/** 复制 13 个权威发布 jar；任一缺失立即失败（文案与迁移前逐字一致）。 */
private fun copyReleaseArtifacts(project: Project, version: String, dist: File) {
    val requiredArtifacts = releaseArtifacts(project, version)
    val missing = requiredArtifacts.filterNot { it.source.isFile }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "[dist] 发布制品缺失：\n" +
                missing.joinToString("\n") { " - ${it.loader}/${it.targetName} ← ${it.source.absolutePath}" } +
                "\n请先运行 :verifyReleasePackaging（全部平台车道已是根子模块，产物由任务依赖保证）。",
        )
    }
    if (dist.exists()) {
        dist.deleteRecursively()
    }
    listOf("bukkit", "fabric", "forge", "neoforge", "sponge").forEach { name ->
        File(dist, name).mkdirs()
    }
    requiredArtifacts.forEach { artifact ->
        val destination = File(File(dist, artifact.loader), artifact.targetName)
        artifact.source.copyTo(destination, overwrite = true)
        project.logger.lifecycle(
            "[dist] ${artifact.loader}/${artifact.targetName}  (${artifact.source.length()} bytes)",
        )
    }

    project.logger.lifecycle("[dist] 完成：${dist.absolutePath}")
}

/**
 * 13 个发布制品的来源与目标名；列表顺序即复制与 `[dist]` 日志顺序。
 */
// 本表是逐行对照的契约清单（车道路径 / 构建相对路径 / 目标名 / loader），拆行后无法逐行比对；
// 行长对此类数据表不是有效信号（.editorconfig 对 .kt/.kts 亦已禁用 ktlint 的 max-line-length）。
@Suppress("MaxLineLength")
private fun releaseArtifacts(project: Project, version: String): List<ReleaseArtifact> =
    listOf(
        laneArtifact(project, ":platform:bukkit:1.12.2", "libs/mpmt-bukkit-1.12.2-$version.jar", "mpmt-bukkit-1.12.2-$version.jar", "bukkit"),
        laneArtifact(project, ":platform:bukkit:1.20.1", "libs/mpmt-bukkit-1.20.1-$version.jar", "mpmt-bukkit-1.20.1-$version.jar", "bukkit"),
        laneArtifact(project, ":platform:bukkit:1.21.1", "libs/mpmt-bukkit-1.21.1-$version.jar", "mpmt-bukkit-1.21.1-$version.jar", "bukkit"),
        laneArtifact(project, ":platform:bukkit:26.2", "libs/mpmt-bukkit-26.2-$version.jar", "mpmt-bukkit-26.2-$version.jar", "bukkit"),
        laneArtifact(project, ":platform:fabric:fabric-1.20.1", "libs/mpmt-fabric-1.20.1-$version.jar", "mpmt-fabric-1.20.1-$version.jar", "fabric"),
        laneArtifact(project, ":platform:fabric:fabric-1.21.1", "libs/mpmt-fabric-1.21.1-$version.jar", "mpmt-fabric-1.21.1-$version.jar", "fabric"),
        laneArtifact(project, ":platform:fabric:fabric-26.2", "libs/mpmt-fabric-26.2-$version.jar", "mpmt-fabric-26.2-$version.jar", "fabric"),
        laneArtifact(project, ":platform:forge:forge-1.20.1", "reobfShadowJar/output.jar", "mpmt-forge-1.20.1-$version.jar", "forge"),
        laneArtifact(project, ":platform:forge:forge-1.21.1", "libs/mpmt-forge-1.21.1-$version.jar", "mpmt-forge-1.21.1-$version.jar", "forge"),
        laneArtifact(project, ":platform:forge:forge-1.12.2", "reobfJar/output.jar", "mpmt-forge-1.12.2-$version.jar", "forge"),
        laneArtifact(project, ":platform:forge:forge-26.2", "libs/mpmt-forge-26.2-$version.jar", "mpmt-forge-26.2-$version.jar", "forge"),
        laneArtifact(project, ":platform:neoforge:neoforge-1.20.2", "libs/mpmt-neoforge-1.20.2-$version.jar", "mpmt-neoforge-1.20.2-$version.jar", "neoforge"),
        laneArtifact(project, ":platform:sponge:sponge-1.20.1", "libs/mpmt-sponge-1.20.1-$version.jar", "mpmt-sponge-1.20.1-$version.jar", "sponge"),
    )

private fun laneArtifact(
    project: Project,
    projectPath: String,
    buildRelativePath: String,
    targetName: String,
    loader: String,
): ReleaseArtifact =
    ReleaseArtifact(
        project.project(projectPath).layout.buildDirectory.file(buildRelativePath).get().asFile,
        loader,
        targetName,
    )
