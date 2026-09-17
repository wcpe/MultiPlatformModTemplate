import buildconventions.ForgeLaneExtension
import buildconventions.ForgeModules
import buildconventions.configureForgeModernProductJar
import buildconventions.configureForgeReportProperties
import buildconventions.forgeJavaLauncher
import buildconventions.registerForgeAcceptanceJar
import buildconventions.registerForgeModernSourceSets
import buildconventions.requiredForgeRunProperty
import buildconventions.verifyForgeDevSecureJarOutputs
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

// Forge 26.2 车道（根构建子模块）：common + server + client 分目录 → mpmt-forge-26.2-<version>.jar。
// 不可变契约：产物名与路径（无 remapJar，jar 即权威产品 jar）、mods.toml / Mixin / services 断言、
// dev SecureJar 嵌入链、REALSERVER262 报告路径与判定强度（ADR-0014）；MC 26.1+ 无混淆命名（ADR-0022）。
// 打包链路与 dev 链路集中在 build-conventions.forge（ADR-0027）。
plugins {
    id("build-conventions.quality")
    id("java")
    // Forge 26.2 用 WCPE loom 的无混淆变体（architectury-loom 系，为无混淆 Forge 补齐 mcp merge 预补丁管线）；
    // 版本由根 settings 的 pluginManagement 单点 pin。
    id("top.wcpe.loom-no-remap")
    id("build-conventions.forge")
}

// MC 26.2 的 loom 配置期要求守护 JVM 25（loom 对 MC 26.2 有版本硬校验）
if (JavaVersion.current().majorVersion.toInt() < 25) {
    throw GradleException("Forge 26.2 配置期必须使用 Java 25 及以上，当前为 ${System.getProperty("java.version")}")
}

// 本工程为根构建的普通子模块（platform/forge/26.2）；group / version 由根 allprojects 统一提供。

val minecraftVersion = "26.2"
val forgeVersion = "26.2-65.0.9"
val loomVersion = "1.17.2"

// 共享模块（L0-L2）项目路径；platform-spi 项目名为 core:spi；acceptance 仅进验收伴侣，不进产品
val productSharedProjects =
    listOf(
        ":core:domain",
        ":core:runtime",
        ":core:client",
        ":core:server",
        ":core:protocol",
        ":core:spi",
    )
val acceptanceSharedProjects = productSharedProjects + listOf(":modules:acceptance")

base {
    archivesName.set("mpmt-forge-26.2")
}

java {
    toolchain {
        // MC 26.2 运行时要求 Java 25
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// 打包配置：产品 / 验收各自打入的共享模块（依赖接线在下方 dependencies 中按配置名完成）
configurations.create("productBundle")
configurations.create("acceptanceBundle")

// forge 车道参数：版本、产物名与本车道接线差异在此声明；
// 源集、dev SecureJar 嵌入、验收 jar 与报告门、打包校验、契约测试由插件承担。
val laneAliasForgeVersion = forgeVersion
val laneAliasLoomVersion = loomVersion
val laneAliasMinecraftVersion = minecraftVersion
forgeLane {
    mcVersion.set(laneAliasMinecraftVersion)
    forgeVersion.set(laneAliasForgeVersion)
    loomVersion.set(laneAliasLoomVersion)
    targetJavaVersion.set(25)
    laneLabel.set("Forge 26.2")
    // disableObfuscation 下无 remapJar：jar 任务保持默认输出，自身即权威产品 jar
    productTaskName.set("jar")
    archiveExcludes.add("module-info.class")
    productModClass.set("top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge262Mod.class")
    acceptanceModClass.set("top/wcpe/mc/mpmt/platform/forge/modern/acceptance/MpmtForge262AcceptanceMod.class")
    acceptanceJarName.set("mpmt-forge-acceptance-26.2")
    acceptanceJarTitle.set("MPMT Forge 26.2 验收伴侣")
    acceptanceExcludes.set(
        listOf(
            "top/wcpe/mc/mpmt/core/**",
            "top/wcpe/mc/mpmt/protocol/**",
            "top/wcpe/mc/mpmt/platform/spi/**",
            "top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge262Mod.class",
            "top/wcpe/mc/mpmt/platform/forge/modern/client/**",
            "top/wcpe/mc/mpmt/platform/forge/modern/net/**",
        ),
    )
    devModOutputs.set(true)
    unitTestMetadata.set(true)
    acceptanceReportGate.set(true)
    acceptanceGateClientHint.set("runClient")
    acceptanceReportCandidates.set(
        listOf("run-realserver/acceptance-report.txt", "run-acceptance-server/acceptance-report.txt"),
    )
    acceptanceReportHint.set(
        "先跑 runServer + runClient，或提供 server-runtime 后跑 runRealServerAcceptanceHost + 客户端伴侣。",
    )
    modernPackagingVerification.set(true)
    contractTestMainClass.set("top.wcpe.mc.mpmt.platform.forge.modern.contract.Forge262ContractMain")
    contractTestDependsOn.set(listOf("packageArtifacts"))
    contractTestProductTask.set("jar")
    contractTestAcceptanceTask.set("acceptanceJar")
    contractTestProperties.put("mpmt.test.projectDir", projectDir.absolutePath)
    contractTestProperties.put("mpmt.test.minecraftVersion", minecraftVersion)
    contractTestProperties.put("mpmt.test.forgeVersion", forgeVersion)
    contractTestProperties.put("mpmt.test.loomVersion", loomVersion)
    contractTestProperties.put("mpmt.test.gradleVersion", gradle.gradleVersion)
}

// 插件公开 API 需要扩展实例（块外传参用），故在块后取回一次
val forge = extensions.getByType(buildconventions.ForgeLaneExtension::class.java)
// 源集形状（common/server/client 分目录 + acceptance/contractTest）与打包内容装配由插件承担：
// resources 输出并入 classes 目录，使 dev MOD_CLASSES 每 mod 只剩一条 SecureJar 路径
registerForgeModernSourceSets(project)
configureForgeModernProductJar(project, forge)
val acceptanceJar = registerForgeAcceptanceJar(project, forge)

// loom run 的 source / mods 声明直接引用源集对象
val acceptance = sourceSets["acceptance"]

repositories {
    // loom：Mojang libraries 由 loom 自动注入；这里补 Forge 制品（universal/userdev/binarypatcher 等）与常规仓库
    mavenCentral()
    maven { url = uri("https://maven.minecraftforge.net/") }
}

dependencies {
    // arch-loom 三段式声明：原版 MC 本体（26.2 无混淆、无官方 mappings，走 disableObfuscation 管线）
    // + Forge（arch-loom 自行解析 userdev）；共享 JAR 消费为 compileOnly 为主
    minecraft("com.mojang:minecraft:$minecraftVersion")
    "forge"("net.minecraftforge:forge:$forgeVersion")

    // 共享 JAR 仅 compileOnly：FML 模块层会把 runtime 上的 jar 当自动模块，
    // 与 SecureJar 内嵌同名包冲突（split package，如 acceptance vs mpmt_acceptance）。
    // 运行期由 embed* / jar shade 把类放进 mod 目录，不进 module path。
    add("compileOnly", files(productSharedProjects.map { ForgeModules.moduleJar(project, it) }))
    add("productBundle", files(productSharedProjects.map { ForgeModules.moduleJar(project, it) }))

    add("acceptanceCompileOnly", files(acceptanceSharedProjects.map { ForgeModules.moduleJar(project, it) }))
    add("acceptanceBundle", files(ForgeModules.moduleJar(project, ":modules:acceptance")))

    add("testImplementation", files(acceptanceSharedProjects.map { ForgeModules.moduleJar(project, it) }))
    add("testImplementation", platform("org.junit:junit-bom:5.10.3"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
}

val realServerHostRequested = gradle.startParameter.taskNames.any { it.endsWith("runRealServerAcceptanceHost") }
// 质量工具链：装配由 build-conventions.quality 插件承担，此处只声明本车道的偏离项
// （PMD 规则集需要 7.16；SpotBugs 需更高版本以解析新版 classfile；分析 JVM 与本车道目标一致）。
quality {
    pmdToolVersion.set("7.16.0")
    spotbugsToolVersion.set("4.9.8")
    analysisJavaVersion.set(25)
}

val staticQualityTasks =
    listOf(
        "checkstyleMain",
        "pmdMain",
        "spotbugsMain",
        "ktlintCheck",
        "detekt",
    )
val acceptanceServerRunDirectory = if (realServerHostRequested) "run-realserver" else "run-acceptance-server"

loom {
    runs {
        // 配置名决定任务名：acceptanceServer -> runAcceptanceServer（主类由 forge 运行模板注入，
        // userdev config runs.main = net.minecraftforge.bootstrap.ForgeBootstrap）。
        // source 指定任务 classpath 基底；mods 声明经 ForgeModClassesService 写入 MOD_CLASSES
        // （dev SecureJar 的 source_roots 语义）。
        create("acceptanceServer") {
            server()
            configName = "Acceptance Server"
            source(acceptance)
            runDir(acceptanceServerRunDirectory)
            property("forge.logging.console.level", "info")
            property("mpmt.acceptance", "true")
            property("mpmt.acceptance.report", project.file("$acceptanceServerRunDirectory/acceptance-report.txt").absolutePath)
            // 允许 -Pmpmt.acceptance.deadlineMs 覆盖；默认 600s 覆盖慢机冷启动
            property("mpmt.acceptance.deadlineMs", (project.findProperty("mpmt.acceptance.deadlineMs") ?: "600000").toString())
            // loom 的 forge server 模板已自带 nogui 程序参数，无需（不可）重复声明
            mods {
                create("mpmt") {
                    sourceSet(sourceSets["main"])
                }
                create("mpmt_acceptance") {
                    sourceSet(acceptance)
                }
            }
        }
        create("acceptanceClient") {
            client()
            configName = "Acceptance Client"
            source(acceptance)
            runDir("run-acceptance-client")
            property("forge.logging.console.level", "info")
            // quickPlay 作辅；主路径由伴侣 tryAutoConnect 读系统属性 mpmt.acceptance.server
            programArgs("--quickPlayMultiplayer", (project.findProperty("mpmt.acceptance.server") ?: "127.0.0.1").toString())
            mods {
                create("mpmt") {
                    sourceSet(sourceSets["main"])
                }
                create("mpmt_acceptance") {
                    sourceSet(acceptance)
                }
            }
        }
    }
}

// 车道编译目标（版本目标即车道事实；UTF-8 / -Xlint 由 build-conventions.forge 统一接线）
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

val java25Launcher = forgeJavaLauncher(project, 25)

// 读取必需运行属性、报告元数据注入与 dev SecureJar 完整性校验由 build-conventions.forge 承担
fun requiredRunProperty(name: String): String = requiredForgeRunProperty(project, name)

fun installDevAcceptanceMod(runDir: File) {
    val modsDir = File(runDir, "mods")
    fileTree(modsDir) {
        include("mpmt-*.jar")
    }.files.forEach { candidate ->
        if (!candidate.delete()) {
            throw GradleException("无法删除旧版 MPMT 验收 JAR：$candidate")
        }
    }
    copy {
        from(acceptanceJar.flatMap { it.archiveFile })
        into(modsDir)
    }
}

fun prepareAcceptanceServerProperties(runDir: File) {
    val propertiesFile = File(runDir, "server.properties")
    val offlineProperties = mapOf("online-mode" to "false", "enforce-secure-profile" to "false")
    val lines: List<String> = if (propertiesFile.isFile) propertiesFile.readLines(Charsets.UTF_8) else emptyList()
    val updatedKeys = mutableSetOf<String>()
    val updatedLines: MutableList<String> =
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
    offlineProperties.forEach { (key, value) ->
        if (!updatedKeys.contains(key)) {
            updatedLines.add("$key=$value")
        }
    }
    runDir.mkdirs()
    propertiesFile.writeText(updatedLines.joinToString(System.lineSeparator()) + System.lineSeparator(), Charsets.UTF_8)
}

tasks.matching { it.name == "runAcceptanceServer" }.configureEach {
    // packageArtifacts 供报告属性取 jar 路径；prepareDevModOutputs 供 MOD_CLASSES 实际加载
    dependsOn("packageArtifacts", "prepareDevModOutputs")
    if (this is JavaExec) {
        val javaExec = this
        javaLauncher.set(java25Launcher)
        doFirst {
            val runDir = project.file(acceptanceServerRunDirectory)
            if (realServerHostRequested) {
                File(runDir, "eula.txt").writeText("eula=true\n", Charsets.UTF_8)
            }
            prepareAcceptanceServerProperties(runDir)
            installDevAcceptanceMod(runDir)
            configureForgeReportProperties(project, forge, javaExec, false)
            verifyForgeDevSecureJarOutputs(project, forge)
        }
    }
}

tasks.matching { it.name == "runAcceptanceClient" }.configureEach {
    dependsOn("packageArtifacts", "prepareDevModOutputs")
    if (this is JavaExec) {
        val javaExec = this
        javaLauncher.set(java25Launcher)
        doFirst {
            installDevAcceptanceMod(project.file("run-acceptance-client"))
            javaExec.systemProperty("mpmt.acceptance.javaExecutable", java25Launcher.get().executablePath.asFile.absolutePath)
            // 伴侣自动连服地址（含端口）；缺省与 quickPlay 一致
            javaExec.systemProperty("mpmt.acceptance.server", (project.findProperty("mpmt.acceptance.server") ?: "127.0.0.1").toString())
        }
    }
}

// 进程命令行复刻 gradle JavaExec 启动（镜像 fabric launchAcceptanceProcess）：
// jvmArguments = @argfile（classpath）+ internal vmArgs（-Dfabric.dli.* 与全部 -D 属性）；
// 本轮系统属性去重后重注，保证编排注入值覆盖 loom 配置期默认值。
fun launchAcceptanceProcess(task: JavaExec, logFile: File, workDir: File): Process {
    val command: MutableList<Any?> = mutableListOf(task.javaLauncher.get().executablePath.asFile.absolutePath)
    val systemPropertyPrefixes = task.systemProperties.keys.map { "-D$it=" }
    // 必须用 allJvmArgs（镜像 fabric lane）：Windows 下长类路径由 Gradle 写成 argfile，
    // 该 @argfile 只出现在 allJvmArgs 里、不在 jvmArguments 中；漏掉它会导致 java 拿不到类路径，
    // 报 "找不到或无法加载主类 net.fabricmc.devlaunchinjector.Main"。
    command.addAll(task.allJvmArgs.filter { arg -> arg != null && systemPropertyPrefixes.none { arg.startsWith(it) } })
    command.addAll(task.systemProperties.map { (key, value) -> "-D$key=$value" })
    command.addAll(listOf(task.mainClass.get()))
    command.addAll(task.args)
    task.argumentProviders.forEach { provider -> command.addAll(provider.asArguments()) }
    // ProcessBuilder(List<String>) 会做一次底层 arraycopy；执行期由 argumentProviders 惰性产出的
    // 参数可能不是 String（如 File / Path），此时抛 ArrayStoreException：
    // "arraycopy: element type mismatch ... to java.lang.String"。
    // 命令行元素统一按其字符串形式归一，并打印非 String 元素类型以便定位。
    val nonStringTypes = command.filterNotNull().filter { it !is String }.map { it.javaClass.name }.distinct()
    if (nonStringTypes.isNotEmpty()) {
        logger.lifecycle("[realserver] ${task.name} 命令行含非 String 参数，已按字符串归一：$nonStringTypes")
    }
    val normalizedCommand = command.filterNotNull().map { it.toString() }
    logger.lifecycle("[realserver] 启动 ${task.name}…")
    logFile.parentFile.mkdirs()
    return ProcessBuilder(normalizedCommand)
        .directory(workDir)
        .redirectErrorStream(true)
        .redirectOutput(logFile)
        .start()
}

/** 仅起真实 Forge 专用服（须 -Pmpmt.acceptance.artifact.server-runtime=…）；客户端请另开终端 runAcceptanceClient。 */
/** 单 Gradle 编排：起服 → 等端口 → 起客户端伴侣 → 等同轮报告（镜像 fabric lane 的 launchAcceptanceProcess 模式）。 */
tasks.register("runForgeRealServer262Acceptance") {
    group = "verification"
    description = "单 Gradle 编排 Forge 26.2 REALSERVER262 服务端与客户端验收"
    // 同 fabric 26.2：本任务用 ProcessBuilder 直接拉起 run 任务命令行、绕过任务图，
    // 必须显式依赖 loom 的 generateDLIConfig（写出 dev-launch-injector 的 launch.cfg），
    // 否则 dev 启动不注入任何 mod、验收场景不执行 → 超时无报告。
    dependsOn("packageArtifacts", "prepareDevModOutputs", "generateDLIConfig")
    doLast {
        val runId = requiredRunProperty("mpmt.acceptance.runId")
        requiredRunProperty("mpmt.acceptance.matrix")
        requiredRunProperty("mpmt.acceptance.startEpochMs")
        val report = file("run-acceptance-server/acceptance-report.txt")
        val logDir = File(layout.buildDirectory.get().asFile, "acceptance-logs")
        logDir.mkdirs()

        // 编排模式下 run 任务的 doFirst 不会执行，这里等价执行其准备工作
        val serverTask = tasks.named<JavaExec>("runAcceptanceServer").get()
        val clientTask = tasks.named<JavaExec>("runAcceptanceClient").get()
        installDevAcceptanceMod(file("run-acceptance-server"))
        prepareAcceptanceServerProperties(file("run-acceptance-server"))
        configureForgeReportProperties(project, forge, serverTask, false)
        installDevAcceptanceMod(file("run-acceptance-client"))
        clientTask.systemProperty("mpmt.acceptance.javaExecutable", java25Launcher.get().executablePath.asFile.absolutePath)
        clientTask.systemProperty(
            "mpmt.acceptance.server",
            (project.findProperty("mpmt.acceptance.server") ?: "127.0.0.1:25566").toString(),
        )

        var server: Process? = null
        var client: Process? = null
        try {
            // ① 起 dev 服务端
            val serverProcess =
                launchAcceptanceProcess(serverTask, File(logDir, "realserver262-server.log"), file(acceptanceServerRunDirectory))
            server = serverProcess

            // ② 等服务端监听 25566（Java 25 冷启动窗口）
            val portDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300)
            var portOpen = false
            while (System.nanoTime() < portDeadline) {
                if (!serverProcess.isAlive) break
                try {
                    Socket("127.0.0.1", 25566).close()
                    portOpen = true
                    break
                } catch (_: Exception) {
                    Thread.sleep(500)
                }
            }
            if (!portOpen) {
                val tail = serverLogFile0(logDir)
                throw GradleException("[realserver] 服务端未监听 25566：" + System.lineSeparator() + tail)
            }

            // ③ 起客户端伴侣（自连 127.0.0.1:25566；GUI 窗口在用户桌面，场景全自动）
            client = launchAcceptanceProcess(clientTask, File(logDir, "realserver262-client.log"), file("run-acceptance-client"))

            // ④ 等同轮报告（驱动看门狗 600s；此处留 660s 余量）
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(660_000)
            val reportLine = "RUN_ID" + "\t" + runId
            while (System.nanoTime() < deadline) {
                if (report.isFile && report.readText(Charsets.UTF_8).contains(reportLine)) break
                if (!serverProcess.isAlive && !(report.isFile && report.readText(Charsets.UTF_8).contains(reportLine))) break
                Thread.sleep(500)
            }
            if (!report.isFile || !report.readText(Charsets.UTF_8).contains(reportLine)) {
                val serverLog = File(logDir, "realserver262-server.log")
                val tail =
                    if (serverLog.isFile) {
                        serverLog.readLines().takeLast(30).joinToString(System.lineSeparator())
                    } else {
                        ""
                    }
                throw GradleException("[realserver] REALSERVER262 未在截止前生成当前运行报告（$runId）：${report.absolutePath}" + System.lineSeparator() + tail)
            }
            val lines = report.readText(Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
            val last = lines.last()
            if (last != "RESULT PASS") {
                throw GradleException("[realserver] 验收未通过（$last）：${report.absolutePath}")
            }
            logger.lifecycle("[realserver] Forge 26.2 REALSERVER262 报告 PASS：${report.absolutePath}")
        } finally {
            listOf(client, server).forEach { proc ->
                if (proc != null && proc.isAlive) {
                    proc.destroy()
                    if (!proc.waitFor(10, TimeUnit.SECONDS)) proc.destroyForcibly()
                }
            }
        }
    }
}

fun serverLogFile0(logDir: File): String {
    val f = File(logDir, "realserver262-server.log")
    return if (f.isFile) f.readLines().takeLast(30).joinToString(System.lineSeparator()) else "无服务端日志"
}

tasks.register("runRealServerAcceptanceHost") {
    group = "verification"
    description = "通过 Forge runAcceptanceServer 启动真实专用服（不含客户端伴侣；不含报告门）"
    dependsOn(tasks.named("runAcceptanceServer"))
}

tasks.named("check") {
    dependsOn("test", "contractTest", "verifyPackaging", staticQualityTasks, "koverXmlReport")
}
tasks.named("build") {
    dependsOn("packageArtifacts")
}
