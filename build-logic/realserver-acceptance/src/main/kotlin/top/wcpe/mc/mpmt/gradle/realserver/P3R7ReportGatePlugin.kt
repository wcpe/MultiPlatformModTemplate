package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/** 将 P3 R7 严格报告校验放在 build-logic，避免根 Kotlin DSL 依赖插件实现类。 */
class P3R7ReportGatePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.tasks.register("verifyP3R7ReportsStrict") {
            group = "verification"
            description = "校验 P3 R7 三车道的当前权威报告与本轮制品"
            doLast { verify(target) }
        }
    }

    private fun verify(project: Project) {
        val run = required(project, "mpmt.acceptance.runId")
        val start =
            required(project, "mpmt.acceptance.startEpochMs").toLongOrNull()
                ?: throw GradleException("P3 R7 的 mpmt.acceptance.startEpochMs 必须是非负整数")
        if (start < 0L) throw GradleException("P3 R7 的 mpmt.acceptance.startEpochMs 必须是非负整数")
        if (required(project, "mpmt.acceptance.matrix") != "R7") {
            throw GradleException("P3 R7 真服门需要 -Pmpmt.acceptance.matrix=R7")
        }
        val version = project.version.toString()
        verifyPaper(project, run, start, version)
        verifyFabric(project, run, start, version)
        verifyForge(project, run, start, version)
    }

    private fun verifyPaper(project: Project, run: String, start: Long, version: String) {
        val root = project.rootDir
        val bukkit = artifact(root, "platform/bukkit/26.2/build/libs/mpmt-bukkit-26.2-$version.jar")
        val fabric = artifact(root, "platform/fabric/26.2/build/libs/mpmt-fabric-26.2-$version.jar")
        verifyReport(
            "Paper",
            File(root, "platform/bukkit/26.2/build/acceptance/server-report-r7.txt"),
            run,
            start,
            artifacts(
                artifact(root, "platform/bukkit/26.2/build/real-server-acceptance/cache/paper-26.2-71.jar"),
                bukkit,
                artifact(root, "platform/bukkit/26.2/build/libs/mpmt-bukkit-acceptance-26.2-$version.jar"),
                fabric,
                fabric,
            ),
        )
    }

    private fun verifyFabric(project: Project, run: String, start: Long, version: String) {
        val root = project.rootDir
        val fabric = artifact(root, "platform/fabric/26.2/build/libs/mpmt-fabric-26.2-$version.jar")
        verifyReport(
            "Fabric",
            File(root, "platform/fabric/26.2/build/acceptance/server-report-r7.txt"),
            run,
            start,
            artifacts(fabric, fabric, fabric, fabric, fabric),
        )
    }

    private fun verifyForge(project: Project, run: String, start: Long, version: String) {
        val root = project.rootDir
        val report =
            File(root, "platform/forge/26.2/run-realserver/acceptance-report.txt")
                .takeIf(File::isFile)
                ?: File(root, "platform/forge/26.2/run-acceptance-server/acceptance-report.txt")
        val forge = artifact(root, "platform/forge/26.2/build/libs/mpmt-forge-26.2-$version.jar")
        verifyReport(
            "Forge",
            report,
            run,
            start,
            artifacts(
                File(required(project, "mpmt.acceptance.forge.serverRuntime")),
                forge,
                artifact(root, "platform/forge/26.2/build/libs/mpmt-forge-acceptance-26.2-$version.jar"),
                forge,
                artifact(root, "platform/forge/26.2/build/libs/mpmt-forge-acceptance-26.2-$version.jar"),
            ),
        )
    }

    private fun verifyReport(
        lane: String,
        report: File,
        run: String,
        start: Long,
        artifacts: Map<String, File>,
    ) {
        P3R7ReportValidator.verify(report, P3R7ReportExpectation(lane, run, start, artifacts))
    }

    private fun artifacts(
        serverRuntime: File,
        serverProduct: File,
        serverAcceptance: File,
        clientProduct: File,
        clientAcceptance: File,
    ): Map<String, File> =
        linkedMapOf(
            "server-runtime" to serverRuntime,
            "server-product" to serverProduct,
            "server-acceptance" to serverAcceptance,
            "client-product" to clientProduct,
            "client-acceptance" to clientAcceptance,
        )

    private fun artifact(root: File, path: String): File = File(root, path)

    private fun required(project: Project, property: String): String {
        val value = project.providers.gradleProperty(property).orNull?.trim()
        if (value.isNullOrEmpty()) throw GradleException("P3 R7 真服门需要 -P$property")
        return value
    }
}
