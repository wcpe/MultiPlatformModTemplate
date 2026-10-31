package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * 严格报告门的全部校验输入：配置期固化成不可变值（File / String），动作只读它，不捕获 Project。
 *
 * <p>可空字段对应「执行期才发现缺失」的 `-P` 属性：缺失时动作内报与迁移前逐字一致的错误，
 * 不在配置期抛异常（否则任何不带这些参数的构建都会在配置阶段失败）。
 */
private class RealServerStrictGateSpec(
    val rootDir: File,
    val version: String,
    val runId: String?,
    val startEpochMs: String?,
    val matrix: String?,
    val forgeServerRuntime: String?,
)

/** 将 26.2 真服严格报告校验放在 build-logic，避免根 Kotlin DSL 依赖插件实现类。 */
class RealServerReportGatePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.tasks.register("verifyRealServerReportsStrict") {
            group = "verification"
            description = "校验 26.2 三车道的当前权威报告与本轮制品"
            // 配置期把校验需要的一切固化成不可变值：动作只捕获这些值，不捕获 project，
            // 否则配置缓存会因 Project 不可序列化而失败（本仓已开启 configuration-cache）。
            // 取值放在 register 动作内：任务实体化时 version 已是最终值，且属性读取发生在配置期，
            // 携带这些 -P 的构建会被正确纳入配置缓存键。
            val spec =
                RealServerStrictGateSpec(
                    rootDir = target.rootDir,
                    version = target.version.toString(),
                    runId = gateProperty(target, "mpmt.acceptance.runId"),
                    startEpochMs = gateProperty(target, "mpmt.acceptance.startEpochMs"),
                    matrix = gateProperty(target, "mpmt.acceptance.matrix"),
                    forgeServerRuntime = gateProperty(target, "mpmt.acceptance.forge.serverRuntime"),
                )
            doLast { verify(spec) }
        }
    }

    private fun verify(spec: RealServerStrictGateSpec) {
        val run = spec.runId.required("mpmt.acceptance.runId")
        val start = parseStartEpochMs(spec.startEpochMs)
        if (spec.matrix != MATRIX_262) {
            throw GradleException("真服门需要 -Pmpmt.acceptance.matrix=$MATRIX_262")
        }
        val version = spec.version
        verifyPaper(spec.rootDir, run, start, version)
        verifyFabric(spec.rootDir, run, start, version)
        verifyForge(spec, run, start, version)
    }

    /** `startEpochMs` 必须是非负整数：缺失、非数字、负数三种情况共用一句文案。 */
    private fun parseStartEpochMs(raw: String?): Long {
        val start = raw.required("mpmt.acceptance.startEpochMs").toLongOrNull()
        if (start == null || start < 0L) {
            throw GradleException("真服门的 mpmt.acceptance.startEpochMs 必须是非负整数")
        }
        return start
    }

    private fun verifyPaper(root: File, run: String, start: Long, version: String) {
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

    private fun verifyFabric(root: File, run: String, start: Long, version: String) {
        val fabric = artifact(root, "platform/fabric/26.2/build/libs/mpmt-fabric-26.2-$version.jar")
        verifyReport(
            "Fabric",
            File(root, "platform/fabric/26.2/build/acceptance/server-report-realserver262.txt"),
            run,
            start,
            artifacts(fabric, fabric, fabric, fabric, fabric),
        )
    }

    private fun verifyForge(spec: RealServerStrictGateSpec, run: String, start: Long, version: String) {
        val root = spec.rootDir
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
                File(spec.forgeServerRuntime.required("mpmt.acceptance.forge.serverRuntime")),
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

    /** 读 `-P` 属性并在配置期固化；缺失时保留 null，由动作内报与迁移前一致的错误。 */
    private fun gateProperty(project: Project, property: String): String? =
        project.providers
            .gradleProperty(property)
            .orNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** 必需属性：缺失即报迁移前的错误文案。 */
    private fun String?.required(property: String): String =
        this ?: throw GradleException("真服门需要 -P$property")

    private companion object {
        /** 真服严格门只认这一轮矩阵标识。 */
        const val MATRIX_262 = "REALSERVER262"
    }
}
