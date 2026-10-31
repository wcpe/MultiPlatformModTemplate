package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.provider.Property
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 托管后台 Paper 宿主进程的 BuildService（B 增强）。
 *
 * <p>[ensureStarted] 幂等：首次调用时下载 paperclip、准备 runDir、用目标车道声明的 Java 后台启动、
 * 轮询日志直到 `Done (`；后续调用直接返回。
 * <p>[close] 在 build 结束时由 Gradle 调用，强杀进程树。
 *
 * <p>对齐 AllinCore PaperRealServerService 的核心契约，去掉 AllinCore 业务特判
 * （PAPI / 加密 / 代理集群），仅保留 MPMT Bukkit realserver 所需：产品 jar + 验收 jar + 报告路径。
 * <p>jar 缓存解析见 [PaperJarCache]，运行目录铺装见 [PaperRunDirectory]，
 * 插件部署见 [PluginJarDeployer]，命令行拼装见 [PaperLaunchCommand]。
 */
abstract class PaperHostService :
    org.gradle.api.services.BuildService<PaperHostService.Params>,
    AutoCloseable {
    interface Params : org.gradle.api.services.BuildServiceParameters {
        /** Paper 运行目录（跨 build 不整体清空，保留 libraries）。 */
        val runDir: org.gradle.api.file.DirectoryProperty

        /** paperclip jar 缓存目录。 */
        val cacheDir: org.gradle.api.file.DirectoryProperty

        /** 产品插件 jar。 */
        val pluginJar: org.gradle.api.file.RegularFileProperty

        /** 验收驱动插件 jar（可选；不设则不部署）。 */
        val acceptanceDriverJar: org.gradle.api.file.RegularFileProperty

        /** 客户端产品 jar；未设置时复用服务端产品 jar。 */
        val acceptanceClientProductJar: org.gradle.api.file.RegularFileProperty

        /** 客户端验收驱动 jar；未设置时复用服务端验收驱动 jar。 */
        val acceptanceClientAcceptanceJar: org.gradle.api.file.RegularFileProperty

        /** 服务端监听端口。 */
        val port: Property<Int>

        /** Java 可执行文件路径（由目标车道决定）。 */
        val javaExecutable: Property<String>

        /** Paper 目标 MC 版本。 */
        val paperVersion: Property<String>

        /** Paper 运行时冻结 build（可选）。 */
        val paperBuild: Property<Int>

        /** Paper 运行时冻结文件大小（字节）。 */
        val paperJarSizeBytes: Property<Long>

        /** Paper 运行时冻结 SHA-256。 */
        val paperJarSha256: Property<String>

        /** 等待 Paper 就绪超时（分钟）。 */
        val readyTimeoutMinutes: Property<Int>

        /** 全局硬超时（分钟）：就绪后到点强杀。 */
        val globalTimeoutMinutes: Property<Int>

        /** 是否启用验收 JVM 属性。 */
        val acceptanceEnabled: Property<Boolean>

        /** 服务端权威验收报告输出路径。 */
        val acceptanceReportFile: org.gradle.api.file.RegularFileProperty

        /** 场景白名单（可选，逗号分隔）；空=全部。 */
        val acceptanceOnly: Property<String>

        /** 矩阵 id（可选，如 SCHEDULER）。 */
        val acceptanceMatrix: Property<String>

        /** 矩阵本轮运行标识。 */
        val acceptanceRunId: Property<String>

        /** 矩阵本轮开始时间戳（毫秒）。 */
        val acceptanceStartEpochMs: Property<String>

        /** 默认轨报告元数据：git commit。 */
        val acceptanceCommit: Property<String>

        /** 默认轨报告元数据：产品版本。 */
        val acceptanceVersion: Property<String>

        /** 默认轨报告元数据：MC 版本。 */
        val acceptanceMcVersion: Property<String>

        /** 默认轨报告元数据：服务端标识串。 */
        val acceptanceServerVersion: Property<String>

        /** 默认轨报告元数据：产品 jar SHA-256（64 hex）。 */
        val acceptanceProductJarSha256: Property<String>
    }

    private val logger =
        org.gradle.api.logging.Logging
            .getLogger(PaperHostService::class.java)
    private val lock = Any()
    private var process: Process? = null
    private var watchdog: Thread? = null

    /** 幂等确保 Paper 已启动并就绪。 */
    fun ensureStarted() {
        synchronized(lock) {
            if (process?.isAlive == true) {
                logger.lifecycle("[mpmt-realserver] Paper 已在运行，跳过启动")
                return
            }
            startPaper()
        }
    }

    private fun startPaper() {
        val runDir = parameters.runDir.get().asFile
        val port = parameters.port.get()
        val paperJar =
            PaperJarCache(parameters.cacheDir.get().asFile, logger)
                .resolve(parameters.paperVersion.get(), frozenPaperArtifact())
        val artifacts =
            PaperArtifacts(
                product = parameters.pluginJar.get().asFile,
                acceptance = parameters.acceptanceDriverJar.get().asFile,
                clientProduct = parameters.acceptanceClientProductJar.orNull?.asFile,
                clientAcceptance = parameters.acceptanceClientAcceptanceJar.orNull?.asFile,
            )

        PaperRunDirectory.prepare(runDir, port)
        deployPlugins(runDir, artifacts)

        val consoleLog = File(runDir, CONSOLE_LOG_PATH)
        consoleLog.parentFile.mkdirs()
        consoleLog.delete()
        logger.lifecycle(
            "[mpmt-realserver] 启动 Paper：端口=$port 运行目录=${runDir.absolutePath}",
        )
        // 绝不用 inheritIO：管道缓冲满会阻塞子进程
        val proc =
            ProcessBuilder(PaperLaunchCommand.build(parameters, artifacts, paperJar))
                .directory(runDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(consoleLog))
                .start()
        process = proc

        awaitReady(proc, consoleLog, port)
        startWatchdog(proc)
    }

    /** 部署产品插件；验收模式下再部署驱动并清掉上一轮报告。 */
    private fun deployPlugins(runDir: File, artifacts: PaperArtifacts) {
        val pluginsDir = File(runDir, PLUGINS_DIR_NAME).apply { mkdirs() }
        val deployer = PluginJarDeployer(logger)
        deployer.replace(pluginsDir, artifacts.product, LABEL_PRODUCT_JAR)
        if (!parameters.acceptanceEnabled.getOrElse(false)) {
            return
        }
        deployer.replace(pluginsDir, artifacts.acceptance, LABEL_ACCEPTANCE_JAR)
        runCatching {
            parameters.acceptanceReportFile
                .get()
                .asFile
                .delete()
        }
        logger.lifecycle("[mpmt-realserver] acceptance 模式：已部署验收驱动 + 启用 -Dmpmt.acceptance")
    }

    private fun frozenPaperArtifact(): FrozenPaperArtifact? {
        val build = parameters.paperBuild.orNull
        val sizeBytes = parameters.paperJarSizeBytes.orNull
        val sha256 = parameters.paperJarSha256.orNull
        if (build == null && sizeBytes == null && sha256 == null) return null
        check(build != null && sizeBytes != null && sha256 != null) {
            "冻结 Paper 输入必须同时提供 build、大小和 SHA-256"
        }
        return FrozenPaperArtifact(parameters.paperVersion.get(), build, sizeBytes, sha256)
    }

    private fun awaitReady(proc: Process, consoleLog: File, port: Int) {
        val timeoutMin = parameters.readyTimeoutMinutes.get().toLong()
        val deadline = System.currentTimeMillis() + timeoutMin * MILLIS_PER_MINUTE
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                throw org.gradle.api.GradleException(
                    "[mpmt-realserver] Paper 进程在就绪前已退出（退出码=${proc.exitValue()}）。\n" +
                        "完整日志：${consoleLog.absolutePath}\n---- 控制台末尾 $CONSOLE_TAIL_LINES 行 ----\n" +
                        consoleTail(consoleLog),
                )
            }
            if (isConsoleReady(consoleLog)) {
                logger.lifecycle("[mpmt-realserver] Paper 已就绪（检测到 `Done (`），端口=$port")
                return
            }
            Thread.sleep(READY_POLL_INTERVAL_MS)
        }
        destroyProcessTree(proc)
        throw org.gradle.api.GradleException(
            "[mpmt-realserver] Paper 在 $timeoutMin 分钟内未就绪，已强制终止。\n" +
                "完整日志：${consoleLog.absolutePath}\n---- 控制台末尾 $CONSOLE_TAIL_LINES 行 ----\n" +
                consoleTail(consoleLog),
        )
    }

    /** 控制台出现 `Done (` 即视为就绪；日志尚未落盘时按未就绪处理。 */
    private fun isConsoleReady(consoleLog: File): Boolean =
        runCatching {
            consoleLog
                .takeIf { it.exists() }
                ?.useLines { lines -> lines.any { it.contains("Done (") } }
                ?: false
        }.getOrDefault(false)

    private fun startWatchdog(proc: Process) {
        val timeoutMin = parameters.globalTimeoutMinutes.get().toLong()
        val watchdogThread =
            Thread {
                try {
                    val killed = !proc.waitFor(timeoutMin, TimeUnit.MINUTES)
                    if (killed && proc.isAlive) {
                        logger.error(
                            "[mpmt-realserver] 全局硬超时 $timeoutMin 分钟到达，强杀后台 Paper",
                        )
                        destroyProcessTree(proc)
                    }
                } catch (_: InterruptedException) {
                    // 正常清理
                }
            }
        watchdogThread.isDaemon = true
        watchdogThread.name = "mpmt-paper-host-watchdog"
        watchdogThread.start()
        watchdog = watchdogThread
    }

    private fun consoleTail(consoleLog: File): String =
        runCatching { consoleLog.readLines().takeLast(CONSOLE_TAIL_LINES).joinToString("\n") }
            .getOrDefault("（无法读取：${consoleLog.absolutePath}）")

    private fun destroyProcessTree(proc: Process) {
        runCatching { proc.descendants().forEach { it.destroyForcibly() } }
        proc.destroyForcibly()
        runCatching { proc.waitFor(PROCESS_EXIT_WAIT_SECONDS, TimeUnit.SECONDS) }
    }

    override fun close() {
        synchronized(lock) {
            watchdog?.interrupt()
            process?.let {
                if (it.isAlive) {
                    logger.lifecycle("[mpmt-realserver] build 结束，停止后台 Paper")
                    destroyProcessTree(it)
                }
            }
            process = null
        }
    }

    private companion object {
        const val PLUGINS_DIR_NAME = "plugins"
        const val CONSOLE_LOG_PATH = "logs/real-server-console.log"
        const val LABEL_PRODUCT_JAR = "产品插件 jar"
        const val LABEL_ACCEPTANCE_JAR = "验收驱动 jar"

        /** 失败诊断时回显的控制台末尾行数。 */
        const val CONSOLE_TAIL_LINES = 80

        /** 就绪轮询间隔与分钟换算。 */
        const val READY_POLL_INTERVAL_MS = 1_000L
        const val MILLIS_PER_MINUTE = 60_000L

        /** 强杀后等待进程退出的上限。 */
        const val PROCESS_EXIT_WAIT_SECONDS = 30L
    }
}
