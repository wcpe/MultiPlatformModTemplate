package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 任务名：与下游车道脚本按名引用，改动即破坏契约。 */
private const val TASK_ENSURE_HOST = "ensurePaperRealServerHost"
private const val TASK_RUN_ACCEPTANCE = "runRealServerAcceptance"
private const val TASK_VERIFY_REPORT = "verifyMpmtAcceptanceReport"

/** 允许命令行覆盖宿主运行参数的 `-P` 属性名。 */
private const val PROPERTY_PORT = "mpmt.realserver.port"
private const val PROPERTY_READY_TIMEOUT = "mpmt.realserver.readyTimeoutMinutes"
private const val PROPERTY_GLOBAL_TIMEOUT = "mpmt.realserver.timeoutMinutes"
private const val PROPERTY_WAIT_FOR_REPORT = "mpmt.realserver.waitForReport"
private const val WAIT_FOR_REPORT_FLAG = "true"

/** 运行目录与缓存目录相对 build 目录的位置。 */
private const val RUN_DIR_PATH = "real-server-acceptance/run"
private const val CACHE_DIR_PATH = "real-server-acceptance/cache"

/** 报告未显式配置时写入 `build/acceptance/server-report.txt`。 */
private const val DEFAULT_REPORT_PATH = "acceptance/server-report.txt"

/** 等待报告落盘的轮询间隔。 */
private const val REPORT_POLL_INTERVAL_MS = 250L

/** 取不到 git 提交号时的占位值。 */
private const val UNKNOWN_COMMIT = "unknown"

/** 产品 jar 缺失时报告的 SHA-256 占位值长度（64 个十六进制字符）。 */
private const val SHA256_HEX_LENGTH = 64

/**
 * Paper 宿主接线：把 [PaperHostService] 与「起服 / 客户端进服 / 报告门禁」三类任务接上。
 *
 * <p>从 [MpmtRealServerAcceptancePlugin] 抽出，让参数校验、服务注册、任务接线三件事各自独立；
 * 调用点仍必须在 [Project.afterEvaluate] 之后（脚本体与 loom runs 此时才配置完毕）。
 */
internal class PaperHostWiring(
    private val target: Project,
    private val ext: MpmtRealServerAcceptanceExtension,
) {
    fun wire() {
        if (!ext.autoStartPaperHost.getOrElse(false)) {
            return
        }
        prepareInputs()
        val paper = registerHostService(resolveJavaExecutable())
        wireEnsureHostTask(paper)
        wireClientTask(paper)
        wireGateTasks(paper)
        target.logger.lifecycle(
            "[mpmt-realserver] B 增强已接线：clientTask=${ext.clientTaskName.get()} → PaperHostService" +
                "（端口 provider / 报告=${ext.reportFile.get().asFile}）",
        )
    }

    /** 校验 autoStart 的必需输入，并补上报告文件默认位置。 */
    private fun prepareInputs() {
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
            ext.reportFile.set(target.layout.buildDirectory.file(DEFAULT_REPORT_PATH))
        }
    }

    /**
     * 宿主 Java 路径在配置期解析成普通 String：该 provider 的背后是 build 级
     * [JavaToolchainService]，直接放进构建服务参数会让配置缓存无法重建
     * （"No service of type JavaToolchainService"），而构建服务参数必须全部可序列化。
     */
    private fun resolveJavaExecutable(): String =
        target.extensions
            .getByType(JavaToolchainService::class.java)
            .launcherFor { languageVersion.set(ext.paperJavaVersion.map(JavaLanguageVersion::of)) }
            .get()
            .executablePath
            .asFile
            .absolutePath

    private fun registerHostService(javaExecutable: String): Provider<PaperHostService> {
        val serviceName = "mpmtPaperHostService" + target.path.replace(':', '_')
        return target.gradle.sharedServices.registerIfAbsent(
            serviceName,
            PaperHostService::class.java,
        ) {
            configureHostParameters(parameters, javaExecutable)
            configureAcceptanceParameters(parameters)
        }
    }

    /** 宿主进程参数：全部在配置期固化，服务参数里不放 Project 或扩展对象。 */
    private fun configureHostParameters(parameters: PaperHostService.Params, javaExecutable: String) {
        parameters.runDir.set(target.layout.buildDirectory.dir(RUN_DIR_PATH))
        parameters.cacheDir.set(target.layout.buildDirectory.dir(CACHE_DIR_PATH))
        parameters.pluginJar.set(ext.pluginJar)
        parameters.acceptanceDriverJar.set(ext.acceptanceDriverJar)
        parameters.acceptanceClientProductJar.set(ext.acceptanceClientProductJar)
        parameters.acceptanceClientAcceptanceJar.set(ext.acceptanceClientAcceptanceJar)
        // 端口与两个超时的取值优先级一致：命令行 -P > 车道脚本声明 > 扩展默认值。
        parameters.port.set(
            target.providers
                .gradleProperty(PROPERTY_PORT)
                .map { it.trim().toInt() }
                .orElse(ext.paperPort),
        )
        parameters.javaExecutable.set(javaExecutable)
        parameters.paperVersion.set(ext.paperVersion)
        parameters.paperBuild.set(ext.paperBuild)
        parameters.paperJarSizeBytes.set(ext.paperJarSizeBytes)
        parameters.paperJarSha256.set(ext.paperJarSha256)
        parameters.readyTimeoutMinutes.set(
            target.providers
                .gradleProperty(PROPERTY_READY_TIMEOUT)
                .map { it.trim().toInt() }
                .orElse(ext.readyTimeoutMinutes),
        )
        parameters.globalTimeoutMinutes.set(
            target.providers
                .gradleProperty(PROPERTY_GLOBAL_TIMEOUT)
                .map { it.trim().toInt() }
                .orElse(ext.globalTimeoutMinutes),
        )
    }

    /** 报告元数据：缺任一项驱动会以 framework/driver-error 失败，故全部在此固化。 */
    private fun configureAcceptanceParameters(parameters: PaperHostService.Params) {
        parameters.acceptanceEnabled.set(true)
        parameters.acceptanceReportFile.set(ext.reportFile)
        parameters.acceptanceOnly.set(ext.acceptanceOnly)
        parameters.acceptanceMatrix.set(ext.matrix)
        parameters.acceptanceRunId.set(ext.acceptanceRunId)
        parameters.acceptanceStartEpochMs.set(ext.acceptanceStartEpochMs)
        // 按退出码判断：git rev-parse 失败时仍会向 stdout 回显参数 "HEAD"，
        // 只按输出是否为空降级会把 "HEAD" 误当成有效提交号。
        parameters.acceptanceCommit.set(
            target.providers.provider {
                val result =
                    target.providers.exec {
                        commandLine("git", "rev-parse", "HEAD")
                        isIgnoreExitValue = true
                    }
                val revision =
                    result.standardOutput
                        .asText
                        .get()
                        .trim()
                if (result.result.get().exitValue == 0) {
                    revision.ifEmpty { UNKNOWN_COMMIT }
                } else {
                    UNKNOWN_COMMIT
                }
            },
        )
        parameters.acceptanceVersion.set(target.provider { target.version.toString() })
        parameters.acceptanceMcVersion.set(ext.paperVersion)
        parameters.acceptanceServerVersion.set(ext.paperVersion.map { "Paper realserver $it" })
        parameters.acceptanceProductJarSha256.set(
            ext.pluginJar.map { regular ->
                val productJar = regular.asFile
                if (productJar.isFile) {
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(productJar.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                } else {
                    "0".repeat(SHA256_HEX_LENGTH)
                }
            },
        )
    }

    private fun wireEnsureHostTask(paper: Provider<PaperHostService>) {
        if (target.tasks.findByName(TASK_ENSURE_HOST) != null) {
            return
        }
        val hostReportFile = ext.reportFile.get().asFile
        val waitForReport =
            target.providers.gradleProperty(PROPERTY_WAIT_FOR_REPORT).orNull == WAIT_FOR_REPORT_FLAG
        val globalTimeoutMinutes = ext.globalTimeoutMinutes.get().toLong()
        target.tasks.register(TASK_ENSURE_HOST) {
            group = "verification"
            description = "启动托管 Paper 宿主；传 -P$PROPERTY_WAIT_FOR_REPORT=true 时等待验收报告"
            usesService(paper)
            ext.extraDependsOn.getOrElse(emptyList()).forEach { dependency ->
                if (target.tasks.findByName(dependency) != null) {
                    dependsOn(dependency)
                }
            }
            doLast {
                paper.get().ensureStarted()
                if (!waitForReport) {
                    return@doLast
                }
                awaitReport(hostReportFile, globalTimeoutMinutes)
                AcceptanceReportGate.verify(hostReportFile)
            }
        }
    }

    /** 轮询等待报告落盘；超时后交给 [AcceptanceReportGate] 报「文件不存在」。 */
    private fun awaitReport(reportFile: File, timeoutMinutes: Long) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes)
        while (!reportFile.isFile && System.nanoTime() < deadline) {
            Thread.sleep(REPORT_POLL_INTERVAL_MS)
        }
    }

    private fun wireClientTask(paper: Provider<PaperHostService>) {
        val clientTaskName = ext.clientTaskName.get()
        target.tasks.matching { it.name == clientTaskName }.configureEach {
            usesService(paper)
            doFirst {
                // 用任务自身的 logger：捕获 Logger 实例同样会被配置缓存拒绝。
                logger.lifecycle(
                    "[mpmt-realserver] 客户端任务 $clientTaskName 启动前：ensureStarted Paper 宿主",
                )
                paper.get().ensureStarted()
            }
        }
    }

    private fun wireGateTasks(paper: Provider<PaperHostService>) {
        val clientTaskName = ext.clientTaskName.get()
        val clientTaskExists = target.tasks.findByName(clientTaskName) != null
        // 门禁任务依赖客户端（客户端 doFirst 起服 → 进服 → 写报告 → 本任务读）
        target.tasks.matching { it.name == TASK_RUN_ACCEPTANCE }.configureEach {
            usesService(paper)
            if (clientTaskExists) {
                dependsOn(clientTaskName)
            }
            // 确保 jar 已构建
            ext.extraDependsOn.getOrElse(emptyList()).forEach { dependency ->
                if (target.tasks.findByName(dependency) != null) {
                    dependsOn(dependency)
                }
            }
        }
        target.tasks.matching { it.name == TASK_VERIFY_REPORT }.configureEach {
            usesService(paper)
            if (clientTaskExists) {
                mustRunAfter(clientTaskName)
            }
        }
    }
}
