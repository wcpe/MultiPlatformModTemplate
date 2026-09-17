import buildconventions.FabricLaneExtension
import buildconventions.acceptanceReportFile
import buildconventions.injectAcceptanceClientMetadata
import buildconventions.injectAcceptanceServerMetadata
import buildconventions.packagingVerification
import buildconventions.requiredAcceptanceProperty
import buildconventions.verifyAcceptanceRoundReport
import buildconventions.verifyDefaultTrackReport
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.JavaExec
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

// Fabric 26.2 车道（根构建子模块）：common + server + client 分目录 → mpmt-fabric-26.2-<version>.jar。
// 不可变契约：产物名与路径、shadowJar 直接产出产品 jar（MC 26.1+ 无混淆、无 remap，ADR-0022）、
// 唯一 L4（v26_2）、fabric.mod.json 元数据、realserver REALSERVER262 场景清单与判定强度（ADR-0014）。
// gametest 接入层 / Loom run / 验收注入 / 模拟服门禁 / 元数据展开集中在 build-conventions.fabric（ADR-0027）。
plugins {
    id("build-conventions.quality")
    id("top.wcpe.loom-no-remap")
    // 车道约定插件须在 loom 之后应用：gametest 源集要晚于 loom 按现有源集注册 migrate*Mappings 任务的时机创建
    id("build-conventions.fabric")
    id("com.gradleup.shadow") version "8.3.11"
    // 分析类插件（spotbugs / ktlint / detekt / kover）不在车道内声明：由 build-conventions.quality 应用，
    // 版本与类路径由根 plugins{} 单点 pin，重复声明会分裂插件类加载器并破坏 loom 的清单服务。
}

group = "top.wcpe.mc.mpmt"

// 本子模块仅服务 MC 26.2（每版本一个子模块）
val mcVersion = "26.2"
val loaderVersion = "0.19.3"
val fabricApiVersion = "0.155.2+26.2"
val targetJavaVersion = 25

val selectedL4Name = "v26_2"
val unselectedL4Name = "v1_21"
val snakeyamlVersion = "2.2"

// 受控内部 JAR 一律经各子模块 jar 任务产物消费（ADR-0026）：按任务依赖取产物（文件输入、不引入传递依赖），
// 构建顺序由 Gradle 保证，无需文件存在性校验任务。
// 用 withType<Jar>().matching 惰性取任务：named("jar") 会在本项目先于生产者配置时立即抛
// UnknownTaskException（子模块按路径序配置），故不可用。
fun moduleJar(projectPath: String): FileCollection =
    files(project(projectPath).tasks.withType<Jar>().matching { it.name == "jar" })

val domainJar = moduleJar(":core:domain")
val runtimeJar = moduleJar(":core:runtime")
val protocolJar = moduleJar(":core:protocol")
val spiJar = moduleJar(":core:spi")
val serverJar = moduleJar(":core:server")
val clientJar = moduleJar(":core:client")
val fabricApiJar = moduleJar(":platform:fabric:fabric-api")
val acceptanceJar = moduleJar(":modules:acceptance")
val productInternalJars: List<FileCollection> =
    listOf(domainJar, runtimeJar, protocolJar, spiJar, serverJar, clientJar, fabricApiJar)

base {
    // 最终产物名同时标识平台与 MC 目标，避免跨车道串扰
    archivesName.set("mpmt-fabric-$mcVersion")
}

java {
    // Fabric 胶水按 MC 26.2 要求固定 Java 25
    toolchain { languageVersion = JavaLanguageVersion.of(targetJavaVersion) }
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

// ============================================================================
// 质量工具链：装配由 build-conventions.quality 插件统一承担，此处只声明本车道的偏离项
// （PMD 规则集需要 7.16；SpotBugs 需更高版本以解析新版 classfile；分析 JVM 与本车道目标一致）。
// 覆盖率口径、排除过滤器、findsecbugs、lombok.config 输入登记等均由插件负责。
quality {
    pmdToolVersion.set("7.16.0")
    spotbugsToolVersion.set("4.9.8")
    analysisJavaVersion.set(targetJavaVersion)
}

// fabric 车道参数：版本、产品任务与本车道接线差异在此声明；
// gametest 源集与依赖接线、Loom run、验收元数据注入、模拟服门禁、mod 元数据展开、单测系统属性均由插件承担。
val fabric = extensions.getByType(FabricLaneExtension::class.java)
fabric.mcVersion.set(mcVersion)
fabric.targetJavaVersion.set(targetJavaVersion)
fabric.loaderVersion.set(loaderVersion)
fabric.fabricApiVersion.set(fabricApiVersion)
// MC 26.1+ 无混淆：产品 jar 由 shadowJar 直接产出，没有 remapJar
fabric.productTaskName.set("shadowJar")
// 矩阵轨 java 可执行文件取自本车道目标 JDK（25）
fabric.matrixJavaHomeEnvironment.set("MPMT_JAVA25_HOME")
// 单进程真服编排会用 -Pmpmt.acceptance.report 覆盖报告，run 设定的默认报告路径同步采用覆盖值
fabric.acceptanceServerUsesOverriddenReport.set(true)

// 专用配置：需 shade 进产物并 relocate 的内容（core + 第三方运行期依赖）
val shadowBundle: Configuration by configurations.creating

// 单版本构建内：common / server / client 分目录（服客分离、平台只胶水）；L4 已固定拷入 common。
// MC 26.2 已使用无混淆原始命名，不再执行 Loom remap。
sourceSets.named("main") {
    java.setSrcDirs(
        listOf(
            "common/src/main/java",
            "server/src/main/java",
            "client/src/main/java",
        ),
    )
    resources.setSrcDirs(listOf("common/src/main/resources"))
}
sourceSets.named("test") {
    java.setSrcDirs(listOf("common/src/test/java"))
}
val verifyVersionSelection by tasks.registering {
    group = "verification"
    description = "校验本版本构建仅含固定 L4 目录（" + selectedL4Name + "）"
    doLast {
        val javaTree = fileTree("common/src/main/java")
        val hasSelected = javaTree.matching { include("**/version/" + selectedL4Name + "/**") }.files.isNotEmpty()
        val hasUnselected = javaTree.matching { include("**/version/" + unselectedL4Name + "/**") }.files.isNotEmpty()
        if (!hasSelected) throw GradleException("Fabric 版本校验失败：缺少 " + selectedL4Name)
        if (hasUnselected) throw GradleException("Fabric 版本校验失败：混入 " + unselectedL4Name)
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    // Fabric 平台 API：提供网络收发（fabric-networking-api-v1）等；编译期依赖，运行期由宿主提供
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // 共享核心：文件输入没有 POM 传递关系，故显式列出完整闭包并一并 shade。
    productInternalJars.forEach {
        implementation(it)
        shadowBundle(it)
    }

    // 第三方运行期依赖：shade 进产物并 relocate 到 top.wcpe.mc.mpmt.libs.*（ADR-0012，防类冲突的统一约定）
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    shadowBundle("org.yaml:snakeyaml:$snakeyamlVersion")

    // gametest 接入层依赖 realserver 验收平台无关核心（控制协议 / 协调 / 报告 / GameTest 框架）
    "gametestImplementation"(acceptanceJar)

    // 跨栈字节对齐 spike 的纯 JVM 测试
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 打包链路：jar（仅本模块类）→ shadowJar（+core+snakeyaml，relocate，权威产品 jar）
tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    // 仅打入 shadowBundle 指定内容，避免误打入 Minecraft / fabric-loader
    configurations = listOf(shadowBundle)
    // 第三方依赖 relocate，避免与宿主 / 其它插件冲突（ADR-0012）
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    // relocate 只改写类与字节码引用，不动 META-INF/maven 下的原始坐标元数据；剔除之，保持产物洁净
    exclude("META-INF/maven/**")
    // shadow 的 ShadowJar 不把 relocate/exclude 等配置纳入增量/缓存指纹（实测改配置后仍 UP-TO-DATE / FROM-CACHE，
    // 命中陈旧产物）。打包要求确定性反映当前配置，故每次重跑、不参与构建缓存。
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// 打包校验：core shade、snakeyaml relocate、唯一 L4、fabric.mod.json
val verifyPackaging by tasks.registering {
    group = "verification"
    description = "校验 Fabric 产品 jar：core shade、snakeyaml relocate、唯一 L4、mod 元数据"
    dependsOn(tasks.named("shadowJar"), verifyVersionSelection)
    packagingVerification(
        laneLabel = "Fabric",
        mcVersion = mcVersion,
        product = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile },
        acceptance = null,
    ) { product, _ ->
        val selectedPrefix = "top/wcpe/mc/mpmt/platform/fabric/version/$selectedL4Name/"
        val unselectedPrefix = "top/wcpe/mc/mpmt/platform/fabric/version/$unselectedL4Name/"
        must(product.file.name.contains(mcVersion), "产物名未包含 MC 版本")
        mustContain(product, "top/wcpe/mc/mpmt/core/domain/Mpmt.class", "core 类未 shade 进产物")
        mustContain(product, "top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class", "platform-spi 未 shade 进产物")
        mustContainPrefix(product, "top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/", "snakeyaml 未 relocate")
        mustNotBundle(product, listOf("org/yaml/snakeyaml/"), "snakeyaml 原包名残留")
        mustNotBundle(product, listOf("META-INF/maven/org.yaml/"), "snakeyaml Maven 元数据残留")
        mustContain(product, "fabric.mod.json", "产物缺少 fabric.mod.json")
        mustNotBundle(product, listOf("net/minecraft/"), "产物内不应直接包含 Minecraft 类")
        mustContainPrefix(product, selectedPrefix, "缺少选中 L4：$selectedL4Name")
        mustNotBundle(product, listOf(unselectedPrefix), "混入未选中 L4：$unselectedL4Name")
        log("Fabric $mcVersion 打包校验通过：${product.file.name}（条目 ${product.entries.size}）")
    }
}

tasks.named("build") {
    dependsOn(verifyPackaging, verifyVersionSelection)
}

val realRequiredScenarios =
    listOf(
        "acceptance/handshake-success",
        "acceptance/handshake-incompatible",
        "acceptance/machine-code-session",
        "acceptance/ban-reconnect",
        "acceptance/unban-reconnect",
        "acceptance/fragment-crc",
        "acceptance/fragment-timeout-retry-resync",
        "acceptance/session-heartbeat-rtt-timeout",
        "acceptance/capability-eventbus",
        "acceptance/hud-title",
        "acceptance/hud-actionbar",
        "acceptance/hud-toast",
        "acceptance/hud-chat",
        "acceptance/real-round-trip",
    )

fun launchAcceptanceProcess(task: JavaExec, logFile: File, runDirectory: File): Process {
    logFile.parentFile.mkdirs()
    if (!runDirectory.isDirectory && !runDirectory.mkdirs()) {
        throw GradleException("无法创建验收运行目录：${runDirectory.absolutePath}")
    }
    val command = mutableListOf(task.javaLauncher.get().executablePath.asFile.absolutePath)
    // Loom 预置的参数可能与任务在矩阵轨重新注入的同名系统属性重复；以后者为准。
    val systemPropertyPrefixes = task.systemProperties.keys.map { "-D$it=" }
    command += task.allJvmArgs.filterNot { argument -> systemPropertyPrefixes.any(argument::startsWith) }
    command += task.systemProperties.map { (key, value) -> "-D$key=$value" }
    command += task.mainClass.get()
    command += task.args
    task.argumentProviders.forEach { command += it.asArguments().toList() }
    return ProcessBuilder(command)
        .directory(runDirectory)
        .redirectErrorStream(true)
        .redirectOutput(logFile)
        .also { builder -> task.environment.forEach { (key, value) -> builder.environment()[key] = value.toString() } }
        .start()
}

fun awaitAcceptancePort(server: Process, logFile: File, port: Int) {
    // Java 25 首次加载 26.2 资源和模组时会明显慢于热启动，须留出客户端验收前的起服窗口。
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300)
    while (System.nanoTime() < deadline) {
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
            return
        } catch (_: Exception) {
            if (!server.isAlive) break
            Thread.sleep(250)
        }
    }
    val tail = if (logFile.isFile) logFile.readLines().takeLast(30).joinToString("\n") else "无服务端日志"
    throw GradleException("[realserver] 服务端未监听 $port：\n$tail")
}

fun stopAcceptanceProcess(process: Process?) {
    if (process == null || !process.isAlive) return
    process.destroy()
    if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
}

fun prepareAcceptanceServerProperties() {
    val propertiesFile = file("run/server.properties")
    if (!propertiesFile.isFile) {
        throw GradleException("未初始化 Fabric 验收服务端配置：${propertiesFile.absolutePath}")
    }
    val offlineProperties = mapOf("online-mode" to "false", "enforce-secure-profile" to "false")
    val updatedLines =
        propertiesFile.readLines().map { line ->
            val separator = line.indexOf('=')
            val key = if (separator > 0) line.substring(0, separator) else ""
            offlineProperties[key]?.let { "$key=$it" } ?: line
        }
    propertiesFile.writeText(updatedLines.joinToString(System.lineSeparator()) + System.lineSeparator())
}

tasks.register("runFabricRealServer262Acceptance") {
    group = "verification"
    description = "单 Gradle 编排 Fabric 26.2 REALSERVER262 服务端与客户端验收"
    // 本任务用 ProcessBuilder 直接拉起 run 任务的命令行，绕过了 Gradle 的任务图，
    // 因此必须显式依赖 loom 的 generateDLIConfig：它写出 dev-launch-injector 的 launch.cfg，
    // 缺该文件时 dev 启动不会注入任何 mod（表现为服务端/客户端只加载 fabricloader+minecraft，
    // 验收场景永不执行 → 超时无报告）。车道改成根子模块后工程路径变化会换掉 loom 工程缓存目录，
    // 旧的遗留 launch.cfg 不再命中，故此处必须显式声明。
    dependsOn(tasks.named("shadowJar"), "gametestClasses", "generateDLIConfig")
    doLast {
        val matrixId = requiredAcceptanceProperty("REALSERVER262", "mpmt.acceptance.matrix")
        if (matrixId != "REALSERVER262") throw GradleException("该任务仅支持 MATRIX REALSERVER262：$matrixId")
        val runId = requiredAcceptanceProperty(matrixId, "mpmt.acceptance.runId")
        requiredAcceptanceProperty(matrixId, "mpmt.acceptance.startEpochMs")
        val report = acceptanceReportFile(matrixId)
        val serverTask = tasks.named<JavaExec>("runAcceptanceServer").get()
        val clientTask = tasks.named<JavaExec>("runAcceptanceClient").get()
        serverTask.injectAcceptanceServerMetadata(fabric)
        clientTask.injectAcceptanceClientMetadata(fabric)
        prepareAcceptanceServerProperties()
        val logDir = layout.buildDirectory.dir("acceptance").get().asFile
        var server: Process? = null
        var client: Process? = null
        try {
            server = launchAcceptanceProcess(serverTask, File(logDir, "realserver262-server.log"), file("run"))
            awaitAcceptancePort(server, File(logDir, "realserver262-server.log"), 25571)
            client = launchAcceptanceProcess(clientTask, File(logDir, "realserver262-client.log"), file("run-client"))
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(660_000)
            while (System.nanoTime() < deadline) {
                if (report.isFile && report.readText().contains("RUN_ID\t$runId")) break
                Thread.sleep(500)
            }
            if (!report.isFile || !report.readText().contains("RUN_ID\t$runId")) {
                throw GradleException("[realserver] REALSERVER262 未在截止前生成当前运行报告：${report.absolutePath}")
            }
            verifyAcceptanceRoundReport(report, matrixId)
            logger.lifecycle("[realserver] Fabric 26.2 REALSERVER262 报告 PASS：${report.absolutePath}")
        } finally {
            stopAcceptanceProcess(client)
            stopAcceptanceProcess(server)
        }
    }
}

// realserver 验收门禁：严格校验 acceptance v2 + 默认轨 REAL_REQUIRED 全 PASS（ADR-0014）。
// 实跑：① runAcceptanceServer ② runAcceptanceClient（须显示）③ 本任务读报告。
tasks.register("runRealServerAcceptance") {
    group = "verification"
    description =
        "严格校验 Fabric realserver acceptance v2 报告（本车道仅有 REALSERVER262 轨，未显式 -Pmpmt.acceptance.matrix 时按该轨校验）"
    doLast {
        // 26.2 车道没有"默认轨"：其唯一有效矩阵即 REALSERVER262（见 PlatformLane.FABRIC_262.defaultMatrix）。
        // 因此在本轮上下文（带 -Pmpmt.acceptance.runId）下若未显式声明矩阵，就按 REALSERVER262 校验——
        // 这样跨 lane 聚合门（只有一个全局矩阵值，无法逐 lane 区分）也能正确定位本车道的报告。
        // 不带 runId 时保持原有"默认轨"行为，不改变独立调用语义。
        val explicitMatrix = (project.findProperty("mpmt.acceptance.matrix") as String?)?.trim().orEmpty()
        val haveRoundContext =
            !(project.findProperty("mpmt.acceptance.runId") as String?)?.trim().isNullOrEmpty()
        val matrixId = explicitMatrix.ifEmpty { if (haveRoundContext) "REALSERVER262" else "" }
        val report = if (matrixId.isEmpty()) fabric.acceptanceReport.get().asFile else acceptanceReportFile(matrixId)
        // 校验实现与其余车道共用 build-conventions 的单份实现（判定顺序与失败文案逐字保留）。
        verifyDefaultTrackReport(project, report, matrixId, "fabric", realRequiredScenarios)
    }
}

val simRequiredScenarios =
    listOf(
        "acceptance/handshake-success",
        "acceptance/handshake-incompatible",
        "acceptance/machine-code-session",
        "acceptance/ban-reconnect",
        "acceptance/unban-reconnect",
        "acceptance/fragment-crc",
        "acceptance/fragment-timeout-retry-resync",
        "acceptance/session-heartbeat-rtt-timeout",
        "acceptance/capability-eventbus",
        "acceptance/hud-title",
        "acceptance/hud-actionbar",
        "acceptance/hud-toast",
        "acceptance/hud-chat",
        "acceptance/integrated-loopback",
    )

// 模拟服默认轨场景清单交插件门禁（runSimNetworkAcceptance）逐项校验
fabric.simScenarios.set(simRequiredScenarios)
