package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/** 将 26.2 真服严格报告校验放在 build-logic，避免根 Kotlin DSL 依赖插件实现类。 */
class RealServerReportGatePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.tasks.register("verifyRealServerReportsStrict") {
            group = "verification"
            description = "校验 26.2 三车道的当前权威报告与本轮制品"
            doLast { verify(target) }
        }
    }

    private fun verify(project: Project) {
        val run = required(project, "mpmt.acceptance.runId")
        val start =
            required(project, "mpmt.acceptance.startEpochMs").toLongOrNull()
                ?: throw GradleException("真服门的 mpmt.acceptance.startEpochMs 必须是非负整数")
        if (start < 0L) throw GradleException("真服门的 mpmt.acceptance.startEpochMs 必须是非负整数")
        if (required(project, "mpmt.acceptance.matrix") != "REALSERVER262") {
            throw GradleException("真服门需要 -Pmpmt.acceptance.matrix=REALSERVER262")
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
            File(root, "platform/bukkit/26.2/build/acceptance/server-report-realserver262.txt"),
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
            File(root, "platform/fabric/26.2/build/acceptance/server-report-realserver262.txt"),
            run,
            start,
            artifacts(fabric, fabric, fabric, fabric, fabric),
        )
    }

    private fun verifyForge(project: Project, run: String, start: Long, version: String) {
        val root = project.rootDir
        // 候选报告：真实专用服写 run-realserver/，dev 编排写 run-acceptance-server/。
        // 两者可能同时存在（例如上一轮真实服留下的报告），故不能只按"文件是否存在"取第一个：
        // 优先取 RUN_ID 匹配本轮的候选，取不到再退回第一个存在的文件，
        // 由校验器以"不属于本轮"明确失败——既不误读旧报告，也不放过真正的过期。
        val candidates =
            listOf(
                File(root, "platform/forge/26.2/run-realserver/acceptance-report.txt"),
                File(root, "platform/forge/26.2/run-acceptance-server/acceptance-report.txt"),
            ).filter(File::isFile)
        val report =
            candidates.firstOrNull { file ->
                file.readLines().any { line -> line.trim() == "RUN_ID\t$run" }
            } ?: candidates.firstOrNull()
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
        RealServerReportValidator.verify(report, RealServerReportExpectation(lane, run, start, artifacts))
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
        if (value.isNullOrEmpty()) throw GradleException("真服门需要 -P$property")
        return value
    }
}
