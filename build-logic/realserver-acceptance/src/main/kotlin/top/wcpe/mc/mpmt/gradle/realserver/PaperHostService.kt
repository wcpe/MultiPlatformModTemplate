package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.provider.Property
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * 托管后台 Paper 宿主进程的 BuildService（B 增强）。
 *
 * <p>[ensureStarted] 幂等：首次调用时下载 paperclip、准备 runDir、用目标车道声明的 Java 后台启动、
 * 轮询日志直到 `Done (`；后续调用直接返回。
 * <p>[close] 在 build 结束时由 Gradle 调用，强杀进程树。
 *
 * <p>对齐 AllinCore PaperRealServerService 的核心契约，去掉 AllinCore 业务特判
 * （PAPI / 加密 / 代理集群），仅保留 MPMT Bukkit realserver 所需：产品 jar + 验收 jar + 报告路径。
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

        /** 矩阵 id（可选，如 R6）。 */
        val acceptanceMatrix: Property<String>

        /** 矩阵本轮运行标识。 */
        val acceptanceRunId: Property<String>

        /** 矩阵本轮开始时间戳（毫秒）。 */
        val acceptanceStartEpochMs: Property<String>

        /** P1 报告元数据：git commit。 */
        val acceptanceCommit: Property<String>

        /** P1 报告元数据：产品版本。 */
        val acceptanceVersion: Property<String>

        /** P1 报告元数据：MC 版本。 */
        val acceptanceMcVersion: Property<String>

        /** P1 报告元数据：服务端标识串。 */
        val acceptanceServerVersion: Property<String>

        /** P1 报告元数据：产品 jar SHA-256（64 hex）。 */
        val acceptanceProductJarSha256: Property<String>
    }

    private val logger = org.gradle.api.logging.Logging.getLogger(PaperHostService::class.java)
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
        val cacheDir = parameters.cacheDir.get().asFile
        val port = parameters.port.get()
        val mcVersion = parameters.paperVersion.get()

        val paperJar = resolvePaperJar(cacheDir, mcVersion)
        val productJar = parameters.pluginJar.get().asFile
        val acceptanceJar = parameters.acceptanceDriverJar.get().asFile
        val clientProductJar = parameters.acceptanceClientProductJar.orNull?.asFile ?: productJar
        val clientAcceptanceJar = parameters.acceptanceClientAcceptanceJar.orNull?.asFile ?: acceptanceJar

        runDir.mkdirs()
        File(runDir, "eula.txt").writeText("eula=true\n")
        File(runDir, "server.properties").writeText(
            """
            |server-port=$port
            |online-mode=false
            |max-players=20
            |spawn-protection=0
            |level-type=flat
            |generate-structures=false
            |spawn-monsters=true
            |spawn-animals=true
            |spawn-npcs=false
            |difficulty=normal
            |motd=MPMT realserver acceptance
            |enable-command-block=true
            |white-list=false
            |enforce-whitelist=false
            |
            """.trimMargin(),
        )
        val offlineUuid =
            UUID.nameUUIDFromBytes("OfflinePlayer:WCPE".toByteArray(Charsets.UTF_8))
        File(runDir, "ops.json").writeText(
            """
            |[
            |  {
            |    "uuid": "$offlineUuid",
            |    "name": "WCPE",
            |    "level": 4,
            |    "bypassesPlayerLimit": true
            |  }
            |]
            |
            """.trimMargin(),
        )

        val pluginsDir = File(runDir, "plugins").apply { mkdirs() }
        require(productJar.exists()) {
            "[mpmt-realserver] 产品插件 jar 缺失：${productJar.absolutePath}"
        }
        purgeStalePluginJars(pluginsDir, productJar.name)
        deployPluginJarSafely(productJar, File(pluginsDir, productJar.name))

        if (parameters.acceptanceEnabled.getOrElse(false)) {
            require(acceptanceJar.exists()) {
                "[mpmt-realserver] 验收驱动 jar 缺失：${acceptanceJar.absolutePath}"
            }
            purgeStalePluginJars(pluginsDir, acceptanceJar.name)
            deployPluginJarSafely(acceptanceJar, File(pluginsDir, acceptanceJar.name))
            runCatching { parameters.acceptanceReportFile.get().asFile.delete() }
            logger.lifecycle("[mpmt-realserver] acceptance 模式：已部署验收驱动 + 启用 -Dmpmt.acceptance")
        }

        val consoleLog = File(runDir, "logs/real-server-console.log")
        consoleLog.parentFile.mkdirs()
        consoleLog.delete()
        val command =
            buildList {
                add(parameters.javaExecutable.get())
                add("-Xmx1G")
                add("-Xms512M")
                add("-Dfile.encoding=UTF-8")
                add("-Dorg.jline.terminal.dumb=true")
                add("-Dorg.jline.terminal.jansi=false")
                add("-Dorg.jline.terminal.jna=false")
                if (parameters.acceptanceEnabled.getOrElse(false)) {
                    add("-Dmpmt.acceptance=true")
                    add(
                        "-Dmpmt.acceptance.report=${parameters.acceptanceReportFile.get().asFile.absolutePath}",
                    )
                    parameters.acceptanceOnly.getOrElse("").takeIf { it.isNotBlank() }?.let {
                        add("-Dmpmt.acceptance.only=$it")
                    }
                    parameters.acceptanceMatrix.getOrElse("").takeIf { it.isNotBlank() }?.let {
                        add("-Dmpmt.acceptance.matrix=$it")
                    }
                    parameters.acceptanceRunId.getOrElse("").takeIf { it.isNotBlank() }?.let {
                        add("-Dmpmt.acceptance.runId=$it")
                    }
                    parameters.acceptanceStartEpochMs.getOrElse("").takeIf { it.isNotBlank() }?.let {
                        add("-Dmpmt.acceptance.startEpochMs=$it")
                    }
                    add("-Dmpmt.acceptance.javaExecutable=${parameters.javaExecutable.get()}")
                    add("-Dmpmt.acceptance.artifact.server-runtime=${paperJar.absolutePath}")
                    add("-Dmpmt.acceptance.artifact.server-product=${productJar.absolutePath}")
                    add("-Dmpmt.acceptance.artifact.server-acceptance=${acceptanceJar.absolutePath}")
                    add("-Dmpmt.acceptance.artifact.client-product=${clientProductJar.absolutePath}")
                    add("-Dmpmt.acceptance.artifact.client-acceptance=${clientAcceptanceJar.absolutePath}")
                    // P1 权威报告元数据（缺任一项驱动会 ERROR framework/driver-error）
                    parameters.acceptanceCommit.getOrElse("").takeIf { it.isNotBlank() }?.let {
                        add("-Dmpmt.acceptance.commit=$it")
                    }
                    parameters.acceptanceVersion.getOrElse("").takeIf { it.isNotBlank() }?.let {
                        add("-Dmpmt.acceptance.version=$it")
                    }
                    parameters.acceptanceMcVersion.getOrElse("").takeIf { it.isNotBlank() }?.let {
                        add("-Dmpmt.acceptance.mcVersion=$it")
                    }
                    parameters.acceptanceServerVersion.getOrElse("").takeIf { it.isNotBlank() }?.let {
                        add("-Dmpmt.acceptance.serverVersion=$it")
                    }
                    parameters.acceptanceProductJarSha256.getOrElse("").takeIf { it.isNotBlank() }?.let {
                        add("-Dmpmt.acceptance.productJarSha256=$it")
                    }
                    // 全量 acceptance 放大 runner / 截止（仍小于看门狗）
                    add("-Dmpmt.acceptance.deadlineMs=1260000")
                }
                add("-jar")
                add(paperJar.absolutePath)
                add("nogui")
            }
        logger.lifecycle(
            "[mpmt-realserver] 启动 Paper：端口=$port 运行目录=${runDir.absolutePath}",
        )
        // 绝不用 inheritIO：管道缓冲满会阻塞子进程
        val proc =
            ProcessBuilder(command)
                .directory(runDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(consoleLog))
                .start()
        process = proc

        awaitReady(proc, consoleLog, port)
        startWatchdog(proc)
    }

    private fun purgeStalePluginJars(pluginsDir: File, currentJarName: String) {
        val withoutExt = currentJarName.removeSuffix(".jar")
        val lastDash = withoutExt.lastIndexOf('-')
        if (lastDash <= 0) return
        val basePrefix = withoutExt.substring(0, lastDash + 1)
        pluginsDir
            .listFiles { f ->
                f.isFile &&
                    f.name.endsWith(".jar") &&
                    f.name.startsWith(basePrefix) &&
                    f.name != currentJarName
            }?.forEach { stale ->
                if (runCatching { stale.delete() }.getOrDefault(false)) {
                    logger.lifecycle(
                        "[mpmt-realserver] 清理旧版本插件 jar：${stale.name}",
                    )
                }
            }
    }

    private fun deployPluginJarSafely(source: File, target: File) {
        val deadline = System.currentTimeMillis() + JAR_DEPLOY_TIMEOUT_MS
        var lastError = "未知原因"
        var loggedWaiting = false
        while (System.currentTimeMillis() < deadline) {
            val sizeBefore = source.length()
            if (sizeBefore <= 0L || !isLoadablePluginJar(source)) {
                lastError = "源 jar 尚未就绪（size=$sizeBefore）"
                if (!loggedWaiting) {
                    logger.lifecycle("[mpmt-realserver] 源插件 jar 尚未就绪，等待……")
                    loggedWaiting = true
                }
                Thread.sleep(JAR_DEPLOY_RETRY_MS)
                continue
            }
            val copied = runCatching { source.copyTo(target, overwrite = true) }.isSuccess
            val sizeAfter = source.length()
            if (!copied ||
                sizeAfter != sizeBefore ||
                target.length() != sizeBefore ||
                !isLoadablePluginJar(target)
            ) {
                lastError =
                    "部署后校验失败（源拷前=$sizeBefore 源拷后=$sizeAfter 目标=${target.length()}）"
                Thread.sleep(JAR_DEPLOY_RETRY_MS)
                continue
            }
            logger.lifecycle(
                "[mpmt-realserver] 已布置插件 jar：${source.name}（${sizeBefore / 1024} KB）",
            )
            return
        }
        throw org.gradle.api.GradleException(
            "[mpmt-realserver] 插件 jar 在 ${JAR_DEPLOY_TIMEOUT_MS / 1000}s 内未能稳定部署：$lastError" +
                "（源=${source.absolutePath}）",
        )
    }

    private fun isLoadablePluginJar(file: File): Boolean =
        runCatching {
            ZipFile(file).use { zip ->
                zip.getEntry("plugin.yml") != null || zip.getEntry("paper-plugin.yml") != null
            }
        }.getOrDefault(false)

    private fun resolvePaperJar(cacheDir: File, mcVersion: String): File {
        cacheDir.mkdirs()
        cacheDir
            .listFiles { f -> f.name.matches(Regex("paper-$mcVersion-\\d+\\.jar")) }
            ?.maxByOrNull { it.name }
            ?.let {
                logger.lifecycle("[mpmt-realserver] 复用缓存 paperclip jar：${it.name}")
                return it
            }
        val latestUrl = "${FillV3Downloads.ENDPOINT}projects/paper/versions/$mcVersion/builds/latest"
        logger.lifecycle("[mpmt-realserver] 查询 PaperMC 最新构建：$latestUrl")
        val json =
            try {
                FillV3Downloads.fetchText(latestUrl)
            } catch (t: Throwable) {
                throw org.gradle.api.GradleException(
                    "[mpmt-realserver] 访问 PaperMC Fill v3 API 失败：$latestUrl（${t.message}）",
                    t,
                )
            }
        val build = FillV3Downloads.parseLatestBuildId(json)
        val downloadUrl = FillV3Downloads.parseDownloadUrl(json, FillV3Downloads.KEY_PRIMARY)
        val jarName = "paper-$mcVersion-$build.jar"
        val target = File(cacheDir, jarName)
        val tmp = File(cacheDir, "$jarName.part")
        logger.lifecycle("[mpmt-realserver] 下载 paperclip jar：$downloadUrl")
        try {
            FillV3Downloads.download(downloadUrl, tmp)
        } catch (t: Throwable) {
            tmp.delete()
            throw org.gradle.api.GradleException(
                "[mpmt-realserver] 下载 paperclip jar 失败：$downloadUrl（${t.message}）",
                t,
            )
        }
        tmp.renameTo(target)
        logger.lifecycle(
            "[mpmt-realserver] paperclip jar 就绪：${target.name}（${target.length() / 1024 / 1024} MB）",
        )
        return target
    }

    private fun awaitReady(proc: Process, consoleLog: File, port: Int) {
        val timeoutMin = parameters.readyTimeoutMinutes.get().toLong()
        val deadline = System.currentTimeMillis() + timeoutMin * 60_000L
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                throw org.gradle.api.GradleException(
                    "[mpmt-realserver] Paper 进程在就绪前已退出（退出码=${proc.exitValue()}）。\n" +
                        "完整日志：${consoleLog.absolutePath}\n---- 控制台末尾 80 行 ----\n" +
                        consoleTail(consoleLog, 80),
                )
            }
            val ready =
                runCatching {
                    consoleLog
                        .takeIf { it.exists() }
                        ?.useLines { lines -> lines.any { it.contains("Done (") } }
                        ?: false
                }.getOrDefault(false)
            if (ready) {
                logger.lifecycle("[mpmt-realserver] Paper 已就绪（检测到 `Done (`），端口=$port")
                return
            }
            Thread.sleep(1_000)
        }
        destroyProcessTree(proc)
        throw org.gradle.api.GradleException(
            "[mpmt-realserver] Paper 在 $timeoutMin 分钟内未就绪，已强制终止。\n" +
                "完整日志：${consoleLog.absolutePath}\n---- 控制台末尾 80 行 ----\n" +
                consoleTail(consoleLog, 80),
        )
    }

    private fun startWatchdog(proc: Process) {
        val timeoutMin = parameters.globalTimeoutMinutes.get().toLong()
        val t =
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
        t.isDaemon = true
        t.name = "mpmt-paper-host-watchdog"
        t.start()
        watchdog = t
    }

    private fun consoleTail(consoleLog: File, lines: Int): String =
        runCatching { consoleLog.readLines().takeLast(lines).joinToString("\n") }
            .getOrDefault("（无法读取：${consoleLog.absolutePath}）")

    private fun destroyProcessTree(proc: Process) {
        runCatching { proc.descendants().forEach { it.destroyForcibly() } }
        proc.destroyForcibly()
        runCatching { proc.waitFor(30, TimeUnit.SECONDS) }
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

    companion object {
        private const val JAR_DEPLOY_TIMEOUT_MS = 60_000L
        private const val JAR_DEPLOY_RETRY_MS = 500L
    }
}
