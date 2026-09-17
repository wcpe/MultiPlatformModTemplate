package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 26.2 实服验收编排的通用工具（fabric 26.2 / forge 26.2 两车道共用）。
 *
 * 车道脚本只保留配置（任务注册、依赖接线、车道特有粘合）；进程拉起、端口等待、进程停止、
 * `server.properties` 准备与验收伴侣安装的实现集中在此。两车道的既有差异（轮询间隔、探活连接超时、
 * 日志尾部行分隔符、缺失键是否补齐、配置缺失时的取值）以参数表达，日志与失败文案逐字保留。
 */

/** 端口探活的默认连接超时（毫秒；fabric 车道取值，forge 车道传 0 = 不设超时，等价 `Socket(host, port)`）。 */
private const val ACCEPTANCE_CONNECT_TIMEOUT_MILLIS = 500

/** 端口探活的默认轮询间隔（毫秒；fabric 车道取值，forge 车道传 500）。 */
private const val ACCEPTANCE_POLL_INTERVAL_MILLIS = 250L

/** 端口等待失败时随异常打印的日志尾部行数。 */
private const val ACCEPTANCE_LOG_TAIL_LINES = 30

/** 停止进程时等待其退出的窗口（秒），超时即强制结束。 */
private const val ACCEPTANCE_STOP_WAIT_SECONDS = 10L

/**
 * 按 gradle `JavaExec` 的任务配置复刻命令行并直接起进程（绕过任务图，供单 Gradle 验收编排使用）。
 *
 * 两车道原本各有一份实现，差异只在此统一（fabric 的命令行形态 + forge 的非 String 归一防御与启动日志）：
 * · 先建日志目录与运行目录；
 * · `allJvmArgs` 去掉与 `systemProperties` 同名前缀的参数后重新注入，保证编排注入值覆盖 loom 配置期默认值；
 * · 必须用 `allJvmArgs`（Windows 下长类路径由 Gradle 写成 argfile，该 @argfile 只出现在 allJvmArgs 里、
 *   不在 jvmArguments 中；漏掉它会导致 java 拿不到类路径，报「找不到或无法加载主类」）；
 * · argumentProviders 惰性产出的参数声明为 List<String>，运行期仍可能混入非 String（如 File / Path），
 *   `ProcessBuilder(List<String>)` 的底层 arraycopy 会抛 ArrayStoreException，故统一按其字符串形式归一，
 *   并在出现非 String 元素时打印其类型便于定位；
 * · 起进程前打印任务名；环境变量按任务声明的 environment 传入。
 */
fun launchAcceptanceProcess(task: JavaExec, logFile: File, workDir: File): Process {
    logFile.parentFile.mkdirs()
    if (!workDir.isDirectory && !workDir.mkdirs()) {
        throw GradleException("无法创建验收运行目录：${workDir.absolutePath}")
    }
    val command: MutableList<String> = mutableListOf(task.javaLauncher.get().executablePath.asFile.absolutePath)
    // Loom 预置的参数可能与任务在矩阵轨重新注入的同名系统属性重复；以后者为准。
    val systemPropertyPrefixes = task.systemProperties.keys.map { "-D$it=" }
    command += task.allJvmArgs.filterNot { argument -> systemPropertyPrefixes.any(argument::startsWith) }
    command += task.systemProperties.map { (key, value) -> "-D$key=$value" }
    command += task.mainClass.get()
    command += task.args
    val providerArguments: MutableList<Any?> = mutableListOf()
    task.argumentProviders.forEach { provider ->
        providerArguments.addAll(provider.asArguments().map { argument -> argument as Any? })
    }
    val nonStringTypes = providerArguments.filterNotNull().filter { it !is String }.map { it.javaClass.name }.distinct()
    if (nonStringTypes.isNotEmpty()) {
        task.logger.lifecycle("[realserver] ${task.name} 命令行含非 String 参数，已按字符串归一：$nonStringTypes")
    }
    command += providerArguments.map { it.toString() }
    task.logger.lifecycle("[realserver] 启动 ${task.name}…")
    return ProcessBuilder(command)
        .directory(workDir)
        .redirectErrorStream(true)
        .redirectOutput(logFile)
        .also { builder -> task.environment.forEach { (key, value) -> builder.environment()[key] = value.toString() } }
        .start()
}

/**
 * 等待服务端监听 [port]：socket 探活，截止窗口 300 秒（Java 25 首次加载 26.2 资源和模组时会明显慢于热启动，
 * 两车道同值，须留出客户端验收前的起服窗口）。
 *
 * 两车道差异以参数给出：
 * · fabric 车道：轮询间隔 250ms、探活连接超时 500ms、尾部行分隔符 `"\n"`（全部取默认值）；
 * · forge 车道：轮询间隔 500ms、探活连接超时 0（无限，等价 `Socket(host, port)`）、尾部行分隔符
 *   `System.lineSeparator()`。
 *
 * 失败文案逐字保留：`[realserver] 服务端未监听 <port>：` + 尾部行分隔符 + 日志末尾 30 行
 * （日志缺失时为「无服务端日志」）。
 */
// 六个参数即两车道的全部既有差异（两处默认节奏 + 一处分隔符，其余为必需上下文），
// 收进配置对象只会把同一份契约拆成两个类型，故保留平铺签名并抑制该规则；
// 300 秒窗口是两车道的既有契约文本（`TimeUnit.SECONDS.toNanos(300)` 被车道契约断言锁定），不外提为常量。
@Suppress("LongParameterList", "MagicNumber")
fun awaitAcceptancePort(
    server: Process,
    logFile: File,
    port: Int,
    pollIntervalMillis: Long = ACCEPTANCE_POLL_INTERVAL_MILLIS,
    connectTimeoutMillis: Int = ACCEPTANCE_CONNECT_TIMEOUT_MILLIS,
    tailSeparator: String = "\n",
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300)
    while (System.nanoTime() < deadline) {
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMillis) }
            return
        } catch (_: Exception) {
            if (!server.isAlive) break
            Thread.sleep(pollIntervalMillis)
        }
    }
    val tail =
        if (logFile.isFile) {
            logFile.readLines().takeLast(ACCEPTANCE_LOG_TAIL_LINES).joinToString(tailSeparator)
        } else {
            "无服务端日志"
        }
    throw GradleException("[realserver] 服务端未监听 $port：$tailSeparator$tail")
}

/** 停止验收进程：已退出或未启动时直接返回；先 destroy，10 秒未退出再 destroyForcibly。 */
fun stopAcceptanceProcess(process: Process?) {
    if (process == null || !process.isAlive) return
    process.destroy()
    if (!process.waitFor(ACCEPTANCE_STOP_WAIT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
}

/**
 * 准备验收服务端的 `server.properties`：关闭线上认证，允许本地 dev 客户端连接。
 *
 * · [requireExisting] = true（fabric 车道）：`server.properties` 必须已存在，否则抛
 *   `未初始化 Fabric 验收服务端配置：<path>`；= false（forge 车道）：可不存在，按新建处理；
 * · [fillMissingKeys] = true（forge 车道）：缺失键追加到文件末尾；= false（fabric 车道）：只改写已有键。
 */
fun prepareAcceptanceServerProperties(runDir: File, requireExisting: Boolean, fillMissingKeys: Boolean) {
    val propertiesFile = File(runDir, "server.properties")
    if (requireExisting && !propertiesFile.isFile) {
        throw GradleException("未初始化 Fabric 验收服务端配置：${propertiesFile.absolutePath}")
    }
    val offlineProperties = mapOf("online-mode" to "false", "enforce-secure-profile" to "false")
    val lines = if (propertiesFile.isFile) propertiesFile.readLines(Charsets.UTF_8) else emptyList()
    val updatedKeys = mutableSetOf<String>()
    val updatedLines =
        lines
            .map { line ->
                val separator = line.indexOf('=')
                val key = if (separator > 0) line.substring(0, separator) else ""
                val offlineValue = offlineProperties[key]
                if (offlineValue != null) {
                    updatedKeys.add(key)
                    "$key=$offlineValue"
                } else {
                    line
                }
            }.toMutableList()
    if (fillMissingKeys) {
        offlineProperties.forEach { (key, value) ->
            if (!updatedKeys.contains(key)) updatedLines.add("$key=$value")
        }
    }
    runDir.mkdirs()
    propertiesFile.writeText(updatedLines.joinToString(System.lineSeparator()) + System.lineSeparator(), Charsets.UTF_8)
}

/**
 * 把独立验收伴侣 JAR 装进运行目录的 `mods/`：先删除旧版 `mpmt-*.jar`（避免 FML 重复加载），再拷入当前产物。
 */
fun installDevAcceptanceMod(runDir: File, acceptanceJar: Provider<RegularFile>) {
    val modsDir = File(runDir, "mods")
    modsDir
        .listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.startsWith("mpmt-") && it.name.endsWith(".jar") }
        .forEach { candidate ->
            if (!candidate.delete()) {
                throw GradleException("无法删除旧版 MPMT 验收 JAR：$candidate")
            }
        }
    modsDir.mkdirs()
    val jar = acceptanceJar.get().asFile
    jar.copyTo(File(modsDir, jar.name), overwrite = true)
}
