package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * MPMT 真服验收编排约定插件。
 *
 * <p>B 完整：全服务端覆盖；客户端由各 loader 自有 gametest/acceptance 进服。
 * <p>B 增强：可选 [PaperHostService] 在客户端任务 doFirst 自动起 Paper 宿主
 *（须 [MpmtRealServerAcceptanceExtension.autoStartPaperHost]=true 并配置 jar）。
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
        ext.paperVersion.convention("1.20.1")
        ext.paperJavaVersion.convention(17)
        ext.paperPort.convention(25599)
        ext.readyTimeoutMinutes.convention(8)
        ext.globalTimeoutMinutes.convention(24)
        ext.acceptanceOnly.convention("")
        ext.acceptanceRunId.convention("")
        ext.acceptanceStartEpochMs.convention("")

        target.tasks.register("verifyMpmtAcceptanceReport") {
            group = "verification"
            description = "读取 reportFile 并校验末行 RESULT PASS"
            doLast {
                val report =
                    ext.reportFile.orNull
                        ?: throw org.gradle.api.GradleException(
                            "未配置 mpmtRealServerAcceptance.reportFile",
                        )
                AcceptanceReportGate.verify(report.asFile)
                val lane = ext.laneId.orNull?.takeIf { it.isNotBlank() } ?: target.name
                logger.lifecycle(
                    "[mpmt-realserver] lane=$lane 报告 PASS：${report.asFile.absolutePath}",
                )
            }
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
    }

    private fun configureHostWiring(target: Project, ext: MpmtRealServerAcceptanceExtension) {
        if (!ext.autoStartPaperHost.getOrElse(false)) {
            return
        }
        require(ext.clientTaskName.isPresent) {
            "[mpmt-realserver] autoStartPaperHost=true 时 clientTaskName 必填"
        }
        require(ext.pluginJar.isPresent) {
            "[mpmt-realserver] autoStartPaperHost=true 时 pluginJar 必填"
        }
        require(ext.acceptanceDriverJar.isPresent) {
            "[mpmt-realserver] autoStartPaperHost=true 时 acceptanceDriverJar 必填"
        }
        if (!ext.reportFile.isPresent) {
            ext.reportFile.set(
                target.layout.buildDirectory.file("acceptance/server-report.txt"),
            )
        }

        val javaToolchains = target.extensions.getByType(JavaToolchainService::class.java)
        val javaExec =
            javaToolchains
                .launcherFor { languageVersion.set(ext.paperJavaVersion.map(JavaLanguageVersion::of)) }
                .map { it.executablePath.asFile.absolutePath }

        val port =
            target.providers
                .gradleProperty("mpmt.realserver.port")
                .map { it.trim().toInt() }
                .orElse(ext.paperPort)
        val readyTimeout =
            target.providers
                .gradleProperty("mpmt.realserver.readyTimeoutMinutes")
                .map { it.trim().toInt() }
                .orElse(ext.readyTimeoutMinutes)
        val globalTimeout =
            target.providers
                .gradleProperty("mpmt.realserver.timeoutMinutes")
                .map { it.trim().toInt() }
                .orElse(ext.globalTimeoutMinutes)

        val paperVersion = ext.paperVersion
        val productJarProvider = ext.pluginJar
        val serviceName = "mpmtPaperHostService" + target.path.replace(':', '_')
        val commitProvider =
            target.providers
                .exec {
                    commandLine("git", "rev-parse", "HEAD")
                    isIgnoreExitValue = true
                }.standardOutput.asText
                .map { it.trim() }
                .orElse("unknown")
        val productShaProvider =
            productJarProvider.map { regular ->
                val f = regular.asFile
                if (!f.isFile) {
                    "0".repeat(64)
                } else {
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(f.readBytes())
                        .joinToString("") { b -> "%02x".format(b) }
                }
            }

        val paper =
            target.gradle.sharedServices.registerIfAbsent(
                serviceName,
                PaperHostService::class.java,
            ) {
                parameters.runDir.set(
                    target.layout.buildDirectory.dir("real-server-acceptance/run"),
                )
                parameters.cacheDir.set(
                    target.layout.buildDirectory.dir("real-server-acceptance/cache"),
                )
                parameters.pluginJar.set(ext.pluginJar)
                parameters.acceptanceDriverJar.set(ext.acceptanceDriverJar)
                parameters.acceptanceClientProductJar.set(ext.acceptanceClientProductJar)
                parameters.acceptanceClientAcceptanceJar.set(ext.acceptanceClientAcceptanceJar)
                parameters.port.set(port)
                parameters.javaExecutable.set(javaExec)
                parameters.paperVersion.set(ext.paperVersion)
                parameters.paperBuild.set(ext.paperBuild)
                parameters.paperJarSizeBytes.set(ext.paperJarSizeBytes)
                parameters.paperJarSha256.set(ext.paperJarSha256)
                parameters.readyTimeoutMinutes.set(readyTimeout)
                parameters.globalTimeoutMinutes.set(globalTimeout)
                parameters.acceptanceEnabled.set(true)
                parameters.acceptanceReportFile.set(ext.reportFile)
                parameters.acceptanceOnly.set(ext.acceptanceOnly)
                parameters.acceptanceMatrix.set(ext.matrix)
                parameters.acceptanceRunId.set(ext.acceptanceRunId)
                parameters.acceptanceStartEpochMs.set(ext.acceptanceStartEpochMs)
                parameters.acceptanceCommit.set(commitProvider)
                parameters.acceptanceVersion.set(target.provider { target.version.toString() })
                parameters.acceptanceMcVersion.set(paperVersion)
                parameters.acceptanceServerVersion.set(
                    paperVersion.map { "Paper realserver $it" },
                )
                parameters.acceptanceProductJarSha256.set(productShaProvider)
            }

        val clientTaskName = ext.clientTaskName.get()
        val log = target.logger
        val clientTaskExists = target.tasks.findByName(clientTaskName) != null
        if (target.tasks.findByName("ensurePaperRealServerHost") == null) {
            target.tasks.register("ensurePaperRealServerHost") {
                group = "verification"
                description = "启动托管 Paper 宿主；传 -Pmpmt.realserver.waitForReport=true 时等待验收报告"
                usesService(paper)
                ext.extraDependsOn.getOrElse(emptyList()).forEach { dependency ->
                    if (target.tasks.findByName(dependency) != null) {
                        dependsOn(dependency)
                    }
                }
                doLast {
                    paper.get().ensureStarted()
                    if (target.providers.gradleProperty("mpmt.realserver.waitForReport").orNull != "true") {
                        return@doLast
                    }
                    val report = ext.reportFile.get().asFile
                    val deadline =
                        System.nanoTime() +
                            TimeUnit.MINUTES.toNanos(ext.globalTimeoutMinutes.get().toLong())
                    while (!report.isFile && System.nanoTime() < deadline) {
                        Thread.sleep(250)
                    }
                    AcceptanceReportGate.verify(report)
                }
            }
        }
        target.tasks.matching { it.name == clientTaskName }.configureEach {
            usesService(paper)
            doFirst {
                log.lifecycle(
                    "[mpmt-realserver] 客户端任务 $clientTaskName 启动前：ensureStarted Paper 宿主",
                )
                paper.get().ensureStarted()
            }
        }

        // 门禁任务依赖客户端（客户端 doFirst 起服 → 进服 → 写报告 → 本任务读）
        target.tasks.matching { it.name == "runRealServerAcceptance" }.configureEach {
            usesService(paper)
            if (clientTaskExists) {
                dependsOn(clientTaskName)
            }
            // 确保 jar 已构建
            ext.extraDependsOn.getOrElse(emptyList()).forEach { dep ->
                if (target.tasks.findByName(dep) != null) {
                    dependsOn(dep)
                }
            }
        }

        target.tasks.matching { it.name == "verifyMpmtAcceptanceReport" }.configureEach {
            usesService(paper)
            if (clientTaskExists) {
                mustRunAfter(clientTaskName)
            }
        }

        log.lifecycle(
            "[mpmt-realserver] B 增强已接线：clientTask=$clientTaskName → PaperHostService" +
                "（端口 provider / 报告=${ext.reportFile.get().asFile}）",
        )
    }
}
