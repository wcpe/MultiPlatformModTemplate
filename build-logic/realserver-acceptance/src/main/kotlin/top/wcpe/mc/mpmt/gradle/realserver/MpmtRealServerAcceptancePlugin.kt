package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 扩展默认值：车道脚本不显式声明时沿用的真服宿主基线。
 * 端口取 25599 是为了与既有 Bukkit 车道脚本的外部约定保持一致。
 */
private const val DEFAULT_PAPER_VERSION = "1.20.1"
private const val DEFAULT_PAPER_JAVA_VERSION = 17
private const val DEFAULT_PAPER_PORT = 25599
private const val DEFAULT_READY_TIMEOUT_MINUTES = 8
private const val DEFAULT_GLOBAL_TIMEOUT_MINUTES = 24

/**
 * MPMT 真服验收编排约定插件。
 *
 * <p>B 完整：全服务端覆盖；客户端由各 loader 自有 gametest/acceptance 进服。
 * <p>B 增强：可选 [PaperHostService] 在客户端任务 doFirst 自动起 Paper 宿主
 *（须 [MpmtRealServerAcceptanceExtension.autoStartPaperHost]=true 并配置 jar）；接线细节见 [PaperHostWiring]。
 *
 * <p>铁律：BuildService 注册在**应用本插件的构建**的 [Project.getGradle] 上；
 * 客户端接线用 `tasks.matching{}.configureEach` 并延后到 [Project.afterEvaluate]。
 */
class MpmtRealServerAcceptancePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val ext =
            target.extensions.create(
                "mpmtRealServerAcceptance",
                MpmtRealServerAcceptanceExtension::class.java,
            )
        ext.matrix.convention("")
        ext.laneId.convention("")
        ext.extraDependsOn.convention(emptyList())
        ext.autoStartPaperHost.convention(false)
        ext.paperVersion.convention(DEFAULT_PAPER_VERSION)
        ext.paperJavaVersion.convention(DEFAULT_PAPER_JAVA_VERSION)
        ext.paperPort.convention(DEFAULT_PAPER_PORT)
        ext.readyTimeoutMinutes.convention(DEFAULT_READY_TIMEOUT_MINUTES)
        ext.globalTimeoutMinutes.convention(DEFAULT_GLOBAL_TIMEOUT_MINUTES)
        ext.acceptanceOnly.convention("")
        ext.acceptanceRunId.convention("")
        ext.acceptanceStartEpochMs.convention("")

        target.tasks.register("verifyMpmtAcceptanceReport") {
            group = "verification"
            description = "读取 reportFile 并校验末行 RESULT PASS"
        }

        target.tasks.register("listMpmtRealServerLanes") {
            group = "help"
            description = "打印 B 车道全服务端 + 自有 gametest 客户端覆盖表"
            doLast {
                logger.lifecycle("[mpmt-realserver] B 车道（全服务端 / 自有客户端伴侣）：")
                PlatformLaneCatalog.summaryLines().forEach { logger.lifecycle("  - $it") }
            }
        }

        if (target.tasks.findByName("runRealServerAcceptance") == null) {
            target.tasks.register("runRealServerAcceptance") {
                group = "verification"
                description =
                    "本平台 realserver 门禁：校验权威报告 RESULT PASS" +
                    "（先由本平台服 + 自有 gametest 客户端进服写出报告）"
                dependsOn("verifyMpmtAcceptanceReport")
            }
        }

        // B 增强接线：脚本体配置扩展 + loom runs 都在 apply 之后，延后 afterEvaluate
        target.afterEvaluate { configureHostWiring(target, ext) }
        // 报告门取值：reportFile / laneId 由车道脚本在 `mpmtRealServerAcceptance { }` 里设置，
        // apply 期读不到，故同样延后到 afterEvaluate。此时扩展已最终，把值固化成普通 File / String，
        // 动作只捕获它们、不捕获 project 与扩展对象，配置缓存才能在开启时正常存储。
        target.afterEvaluate { configureReportGateValues(target, ext) }
    }

    private fun configureReportGateValues(target: Project, ext: MpmtRealServerAcceptanceExtension) {
        val reportFile = ext.reportFile.orNull?.asFile
        val laneName = ext.laneId.orNull?.takeIf { it.isNotBlank() } ?: target.name
        target.tasks.named("verifyMpmtAcceptanceReport") {
            doLast {
                val report =
                    reportFile
                        ?: throw org.gradle.api.GradleException(
                            "未配置 mpmtRealServerAcceptance.reportFile",
                        )
                AcceptanceReportGate.verify(report)
                logger.lifecycle(
                    "[mpmt-realserver] lane=$laneName 报告 PASS：${report.absolutePath}",
                )
            }
        }
    }

    private fun configureHostWiring(target: Project, ext: MpmtRealServerAcceptanceExtension) {
        PaperHostWiring(target, ext).wire()
    }
}
