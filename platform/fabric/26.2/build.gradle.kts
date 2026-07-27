import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.jvm.tasks.ProcessResources
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

// platform-fabric-26.2（L3）：MC 26.2 独立构建；common/server/client 分目录，Loom 根打包。
// 关键链路（ADR-0012）：core 纯 Java 经 shadow shade 进产物，snakeyaml relocate；
// MC 26.1+ 使用 Mojang 无混淆原始命名，shadowJar 直接产出权威产品 jar（ADR-0022）。

plugins {
    id("net.fabricmc.fabric-loom") version "1.17-wcpe-4"
    id("com.gradleup.shadow") version "8.3.11"
    // 静态分析 / 质量工具链（严格门禁，static-analysis.md）：与根构建同一套，共享仓库根 config/ 规则集。
    // 核心 Gradle 插件经 apply(plugin=...) 接入（见下方装配块）；外部插件在此带版本直接 apply。
    id("com.github.spotbugs") version "6.0.26"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
}

// 坐标与版本：独立 includeBuild 需自行设定（版本唯一来源仍为根 VERSION 文件）
group = "top.wcpe.mc.mpmt"
version = rootProject.file("../../../VERSION").readText().trim()

// 本构建仅服务 MC 26.2（每版本独立 includeBuild，废除 -P 选型）
val mcVersion = "26.2"
val loaderVersion = "0.19.3"
val fabricApiVersion = "0.155.2+26.2"
val targetJavaVersion = 25
val selectedL4Name = "v26_2"
val unselectedL4Name = "v1_21"
val loaderDependency = loaderVersion
val fabricApiDependency = fabricApiVersion
val snakeyamlVersion = "2.2"
// 依赖 platform-spi（经 api 传递 core-runtime + core-domain），经 includeBuild 依赖替换消费
val platformApiCoordinate = "top.wcpe.mc.mpmt:fabric-api:$version"
val spiCoordinate = "top.wcpe.mc.mpmt:spi:$version"
// 依赖 core-server（服务端网络装配特性 ServerNetworkFeature；经 api 传递 protocol + core-runtime）
val serverCoordinate = "top.wcpe.mc.mpmt:server:$version"
// 依赖 core-client（客户端网络装配特性 ClientNetworkFeature + 弱标识提供者）
val clientCoordinate = "top.wcpe.mc.mpmt:client:$version"
// realserver 验收 harness 平台无关核心（仅 gametest 接入层用，不入产品 jar，ADR-0014）
val acceptanceCoordinate = "top.wcpe.mc.mpmt:acceptance:$version"

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
// 静态分析 / 质量工具链装配（严格门禁，static-analysis.md）——本独立 includeBuild 单工程直接 apply。
// includeBuild 的 rootProject 即本目录，共享规则集在仓库根 config/，故引用 ../config/*；
// .editorconfig / lombok.config 在仓库根，ktlint / Lombok 自动向上查找，无需额外配置。
// 违规即失败构建（isIgnoreFailures=false），与根构建口径一致。
// ============================================================================
// 样式审查：Checkstyle（共享裁剪规则集）
apply(plugin = "checkstyle")
configure<CheckstyleExtension> {
    toolVersion = "10.17.0"
    configFile = rootProject.file("../../../config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}
// 代码异味 / 源码规则：PMD（共享裁剪规则集）
apply(plugin = "pmd")
configure<PmdExtension> {
    toolVersion = "7.16.0"
    isConsoleOutput = true
    ruleSetConfig = resources.text.fromFile(rootProject.file("../../../config/pmd/ruleset.xml"))
    ruleSets = emptyList()
    isIgnoreFailures = false
}
// 测试覆盖率：JaCoCo（仅报告，不设覆盖率底线门禁）。平台胶水单元测试少、靠 realserver 验收，
// 故只产出 xml/html 报告，不并入 check、不加 jacocoTestCoverageVerification。
apply(plugin = "jacoco")
tasks.withType(org.gradle.testing.jacoco.tasks.JacocoReport::class.java).configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
// 缺陷检测（字节码）+ 安全审查：SpotBugs + FindSecBugs（挂在 SpotBugs 上）
configure<SpotBugsExtension> {
    toolVersion.set("4.9.8")
    ignoreFailures.set(false)
    effort.set(Effort.MAX)
    // 报告 MEDIUM 及以上置信度，避免 LOW 置信度噪声拖垮严格门禁
    reportLevel.set(Confidence.MEDIUM)
    excludeFilter.set(rootProject.file("../../../config/spotbugs/exclude.xml"))
}
dependencies.add("spotbugsPlugins", "com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
// 把 lombok.config 登记为编译输入：其改动须失效编译缓存（否则缓存会服旧的、缺 @Generated 的类，
// 导致 SpotBugs/JaCoCo 仍对 Lombok 生成代码误报）。lombok.config 在仓库根，故引用 ../lombok.config。
tasks.withType(JavaCompile::class.java).configureEach {
    inputs.file(rootProject.file("../../../lombok.config"))
        .withPropertyName("lombokConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
// 分析任务固定与目标车道一致的 JDK 启动器（26.2→25）。
// SpotBugs worker 用守护 JVM，无 javaLauncher 属性、不设。
val analysisToolchains = extensions.getByType(JavaToolchainService::class.java)
val analysisLauncher =
    analysisToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
tasks.withType(Checkstyle::class.java).configureEach {
    javaLauncher.set(analysisLauncher)
}
tasks.withType(Pmd::class.java).configureEach {
    javaLauncher.set(analysisLauncher)
}
// 仅生产码（spotbugsMain）严格门禁；test / gametest 等非 main 源集宽松
// （测试与验收 harness 常含 mock/反射等 SpotBugs 噪声，安全/缺陷分析重在生产码）。
tasks.withType(SpotBugsTask::class.java).configureEach {
    if (name != "spotbugsMain") {
        ignoreFailures = true
    }
}

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

// realserver 验收接入层：gametest 模块源码
val gametest: SourceSet by sourceSets.creating {
    java.setSrcDirs(listOf("gametest/src/main/java"))
    resources.setSrcDirs(listOf("gametest/src/main/resources"))
    compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["main"].output
}
configurations["gametestImplementation"].extendsFrom(configurations["implementation"])

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    // Fabric 平台 API：提供网络收发（fabric-networking-api-v1）等；编译期依赖，运行期由宿主提供
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // 共享核心（platform-spi + 传递的 core-runtime/core-domain）：纯 Java、非 mod 依赖、不参与 remap
    implementation(platformApiCoordinate)
    implementation(spiCoordinate)
    shadowBundle(platformApiCoordinate)
    shadowBundle(spiCoordinate)

    // 服务端公共逻辑（core-server + 传递的 protocol）：同样纯 Java、shade 进产物、不参与 remap
    implementation(serverCoordinate)
    shadowBundle(serverCoordinate)

    // 客户端公共逻辑（core-client）：客户端网络装配 + 弱标识提供者，shade 进产物、不参与 remap
    implementation(clientCoordinate)
    shadowBundle(clientCoordinate)

    // 第三方运行期依赖：shade 进产物并 relocate 到 top.wcpe.mc.mpmt.libs.*（ADR-0012，防类冲突的统一约定）
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    shadowBundle("org.yaml:snakeyaml:$snakeyamlVersion")

    // gametest 接入层依赖 realserver 验收平台无关核心（控制协议 / 协调 / 报告 / GameTest 框架）
    "gametestImplementation"(acceptanceCoordinate)

    // 跨栈字节对齐 spike 的纯 JVM 测试
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// realserver 验收的 Loom 运行配置（dev 环境，加载 gametest 源集的 mpmt-acceptance 测试 mod）。
// 服务端可 headless 跑（runAcceptanceServer）；客户端需显示，由用户本机经 quickPlay 自连（runAcceptanceClient）。
val acceptanceReportFile = layout.buildDirectory.file("acceptance/server-report.txt")
val simReportFile = layout.buildDirectory.file("acceptance/sim-report.txt")
loom {
    runs {
        // 模拟服 GameTest 套件（FR-23①）：headless 起服跑 in-process 回环网络 GameTest，无外部客户端、可自动跑
        create("simNetworkTest") {
            server()
            configName = "Sim Network GameTest"
            source(gametest)
            property("mpmt.simtest", "true")
            property("mpmt.simtest.report", simReportFile.get().asFile.absolutePath)
        }
        create("acceptanceServer") {
            server()
            configName = "Acceptance Server"
            source(gametest)
            property("mpmt.acceptance", "true")
            property("mpmt.acceptance.report", acceptanceReportFile.get().asFile.absolutePath)
            // 看门狗绝对截止：须覆盖客户端冷启动 + 首场景 awaitClientReady（常 >3min）
            property("mpmt.acceptance.deadlineMs", "660000")
        }
        create("acceptanceClient") {
            client()
            configName = "Acceptance Client"
            source(gametest)
            // 独立运行目录，避免与服务端 run/ 并发冲突
            runDir("run-client")
            // quickPlay 作首选；1.21 常被无障碍/资源包屏挡住，伴侣另有 ConnectScreen 程序化兜底。
            // 默认对齐 run/server.properties 的 server-port=25571（仅 host 会落到 25565 导致 Connection refused）。
            val acceptanceServerAddr =
                (project.findProperty("mpmt.acceptance.server") as String?) ?: "127.0.0.1:25571"
            programArgs("--quickPlayMultiplayer", acceptanceServerAddr)
            // 伴侣自连读系统属性；与 programArgs 同源，避免只改 -P 时伴侣仍连默认口
            property("mpmt.acceptance.server", acceptanceServerAddr)
        }
    }
}

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

fun requiredMatrixProperty(matrixId: String, name: String): String {
    val value = (project.findProperty(name) as String?)?.trim().orEmpty()
    if (value.isEmpty()) {
        throw GradleException("矩阵 $matrixId 缺少 -P$name")
    }
    return value
}

fun matrixJavaExecutable(): File {
    val javaHome =
        System.getenv("MPMT_JAVA25_HOME")
            ?: System.getProperty("java.home")
            ?: throw GradleException("矩阵轨需要 MPMT_JAVA25_HOME 或当前 java.home")
    val suffix = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
    val executable = file("$javaHome/bin/java$suffix")
    if (!executable.isFile) {
        throw GradleException("找不到 Java 可执行文件：${executable.absolutePath}")
    }
    return executable
}

fun configureAcceptanceServer(task: JavaExec) {
    val productJar = tasks.named<ShadowJar>("shadowJar").get().archiveFile.get().asFile
    val commit =
        providers.exec {
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.get().trim()
    task.systemProperty("mpmt.acceptance.commit", commit)
    task.systemProperty("mpmt.acceptance.version", project.version.toString())
    task.systemProperty("mpmt.acceptance.mcVersion", mcVersion)
    task.systemProperty("mpmt.acceptance.serverVersion", "Fabric realserver $mcVersion")
    task.systemProperty("mpmt.acceptance.productJarSha256", sha256(productJar))

    val matrixId = (project.findProperty("mpmt.acceptance.matrix") as String?)?.trim().orEmpty()
    if (matrixId.isEmpty()) {
        return
    }

    fun artifactOrProduct(property: String): File {
        val raw = (project.findProperty(property) as String?)?.trim().orEmpty()
        return if (raw.isEmpty()) productJar else file(raw)
    }

    val javaExecutable = matrixJavaExecutable()
    val matrixReport =
        (project.findProperty("mpmt.acceptance.report") as String?)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { file(it) }
            ?: layout.buildDirectory.file("acceptance/server-report-${matrixId.lowercase()}.txt").get().asFile
    task.systemProperty("mpmt.acceptance.matrix", matrixId)
    task.systemProperty("mpmt.acceptance.runId", requiredMatrixProperty(matrixId, "mpmt.acceptance.runId"))
    task.systemProperty("mpmt.acceptance.startEpochMs", requiredMatrixProperty(matrixId, "mpmt.acceptance.startEpochMs"))
    task.systemProperty("mpmt.acceptance.javaExecutable", javaExecutable.absolutePath)
    task.systemProperty("mpmt.acceptance.artifact.server-runtime", artifactOrProduct("mpmt.acceptance.artifact.server-runtime").absolutePath)
    task.systemProperty("mpmt.acceptance.artifact.server-product", productJar.absolutePath)
    task.systemProperty("mpmt.acceptance.artifact.server-acceptance", artifactOrProduct("mpmt.acceptance.artifact.server-acceptance").absolutePath)
    task.systemProperty("mpmt.acceptance.artifact.client-product", artifactOrProduct("mpmt.acceptance.artifact.client-product").absolutePath)
    task.systemProperty("mpmt.acceptance.artifact.client-acceptance", artifactOrProduct("mpmt.acceptance.artifact.client-acceptance").absolutePath)
    task.systemProperty("mpmt.acceptance.report", matrixReport.absolutePath)
    logger.lifecycle("[realserver] 矩阵 $matrixId 已注入：runId=${project.findProperty("mpmt.acceptance.runId")} report=${matrixReport.absolutePath}")
}

fun configureAcceptanceClient(task: JavaExec) {
    val matrixId = (project.findProperty("mpmt.acceptance.matrix") as String?)?.trim().orEmpty()
    if (matrixId.isNotEmpty()) {
        task.systemProperty("mpmt.acceptance.javaExecutable", matrixJavaExecutable().absolutePath)
        task.systemProperty("mpmt.acceptance.matrix", matrixId)
    }
}

// 模拟服报告绑定当前提交、版本与实际产品 jar；故障注入类只来自 gametest 源集，不进入该产品 jar。
tasks.named<JavaExec>("runSimNetworkTest") {
    dependsOn(tasks.named("shadowJar"))
    doFirst {
        val productJar = tasks.named<ShadowJar>("shadowJar").get().archiveFile.get().asFile
        val commit =
            providers.exec {
                commandLine("git", "rev-parse", "HEAD")
            }.standardOutput.asText.get().trim()
        systemProperty("mpmt.simtest.commit", commit)
        systemProperty("mpmt.simtest.version", project.version.toString())
        systemProperty("mpmt.simtest.mcVersion", mcVersion)
        systemProperty("mpmt.simtest.serverVersion", "Fabric headless $mcVersion")
        systemProperty("mpmt.simtest.productJarSha256", sha256(productJar))
    }
}

// realserver v2 报告元数据：与模拟服同一套绑定（commit / 版本 / 产品 jar SHA）。
// 矩阵轨（-Pmpmt.acceptance.matrix=Rn）：注入 runId/startEpoch/javaExecutable/五类制品，供 MatrixAcceptanceReportV2。
tasks.named<JavaExec>("runAcceptanceServer") {
    dependsOn(tasks.named("shadowJar"), "gametestClasses")
    doFirst { configureAcceptanceServer(this as JavaExec) }
}

// 矩阵客户端：注入 javaExecutable，便于 ClientReady v2 上报
tasks.named<JavaExec>("runAcceptanceClient") {
    doFirst { configureAcceptanceClient(this as JavaExec) }
}

// fabric.mod.json 占位由选中目标统一注入
val metadataValues =
    mapOf(
        "version" to project.version,
        "minecraftVersion" to mcVersion,
        "javaVersion" to targetJavaVersion,
        "loaderDependency" to loaderDependency,
        "fabricApiDependency" to fabricApiDependency,
    )
tasks.processResources {
    inputs.properties(metadataValues)
    filesMatching("fabric.mod.json") {
        expand(metadataValues)
    }
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
    doLast {
        val jar = tasks.named<ShadowJar>("shadowJar").get().archiveFile.get().asFile
        val entries = ZipFile(jar).use { zf -> zf.entries().asSequence().map { it.name }.toList() }
        val selectedPrefix = "top/wcpe/mc/mpmt/platform/fabric/version/$selectedL4Name/"
        val unselectedPrefix = "top/wcpe/mc/mpmt/platform/fabric/version/$unselectedL4Name/"

        fun must(cond: Boolean, msg: String) {
            if (!cond) throw GradleException("Fabric 打包校验失败：$msg")
        }
        must(jar.name.contains(mcVersion), "产物名未包含 MC 版本")
        must(entries.contains("top/wcpe/mc/mpmt/core/domain/Mpmt.class"), "core 类未 shade 进产物")
        must(entries.contains("top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class"), "platform-spi 未 shade 进产物")
        must(entries.any { it.startsWith("top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/") }, "snakeyaml 未 relocate")
        must(entries.none { it.startsWith("org/yaml/snakeyaml/") }, "snakeyaml 原包名残留")
        must(entries.none { it.startsWith("META-INF/maven/org.yaml/") }, "snakeyaml Maven 元数据残留")
        must(entries.contains("fabric.mod.json"), "产物缺少 fabric.mod.json")
        must(entries.none { it.startsWith("net/minecraft/") }, "产物内不应直接包含 Minecraft 类")
        must(entries.any { it.startsWith(selectedPrefix) }, "缺少选中 L4：$selectedL4Name")
        must(entries.none { it.startsWith(unselectedPrefix) }, "混入未选中 L4：$unselectedL4Name")
        logger.lifecycle("Fabric $mcVersion 打包校验通过：${jar.name}（条目 ${entries.size}）")
    }
}

tasks.named("build") {
    dependsOn(verifyPackaging, verifyVersionSelection)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.named("processGametestResources"))
    systemProperty("mpmt.test.minecraftVersion", mcVersion)
    systemProperty("mpmt.test.javaVersion", targetJavaVersion.toString())
    systemProperty("mpmt.test.archiveName", "mpmt-fabric-$mcVersion")
    systemProperty("mpmt.test.loaderDependency", loaderDependency)
    systemProperty("mpmt.test.fabricApiDependency", fabricApiDependency)
    systemProperty("mpmt.test.projectVersion", project.version.toString())
    // 验收元数据：processGametestResources 注入与产品同口径的 depends
    systemProperty(
        "mpmt.test.acceptanceMetadata",
        layout.buildDirectory.file("resources/gametest/fabric.mod.json").get().asFile.absolutePath,
    )
    systemProperty(
        "mpmt.test.acceptanceArchiveName",
        "mpmt-fabric-acceptance-$mcVersion-${project.version}.jar",
    )
}

// 把 gametest 接入层纳入构建期编译校验（只编译、不运行——运行需真实服）
tasks.named("build") {
    dependsOn("gametestClasses")
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

fun matrixReportFile(matrixId: String): File {
    val custom = (project.findProperty("mpmt.acceptance.report") as String?)?.trim().orEmpty()
    return if (custom.isNotEmpty()) file(custom) else layout.buildDirectory.file("acceptance/server-report-${matrixId.lowercase()}.txt").get().asFile
}

fun verifyMatrixReport(report: File, matrixId: String) {
    val lines = report.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.firstOrNull() != "SERVER-GAMETEST-REPORT v2") throw GradleException("[realserver] 报告不是 acceptance v2")
    val matrixLine = lines.firstOrNull { it.startsWith("MATRIX\t") || it.startsWith("MATRIX ") }
    if (matrixLine == null || !matrixLine.contains(matrixId)) throw GradleException("[realserver] 矩阵报告缺少 MATRIX $matrixId：$matrixLine")
    val runId = requiredMatrixProperty(matrixId, "mpmt.acceptance.runId")
    if (lines.none { it == "RUN_ID\t$runId" || it == "RUN_ID $runId" }) {
        throw GradleException("[realserver] 矩阵 $matrixId 报告不属于当前运行：$runId")
    }
    if (lines.lastOrNull() != "RESULT PASS") throw GradleException("[realserver] 矩阵 $matrixId 未通过：${report.absolutePath}")
    for (id in listOf("product-handshake", "product-roundtrip", "client-hud")) {
        if (lines.none { it.startsWith("SCENARIO\t$id\tPASS") || it.startsWith("PASS $id") || it.contains("\t$id\tPASS") }) {
            throw GradleException("[realserver] 矩阵 $matrixId 缺少公共场景 PASS：$id")
        }
    }
}

fun launchAcceptanceProcess(task: JavaExec, logFile: File, runDirectory: File): Process {
    logFile.parentFile.mkdirs()
    if (!runDirectory.isDirectory && !runDirectory.mkdirs()) {
        throw GradleException("无法创建验收运行目录：${runDirectory.absolutePath}")
    }
    val command = mutableListOf(task.javaLauncher.get().executablePath.asFile.absolutePath)
    command += task.allJvmArgs
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
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
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

tasks.register("runFabricR7Acceptance") {
    group = "verification"
    description = "单 Gradle 编排 Fabric 26.2 R7 服务端与客户端验收"
    dependsOn(tasks.named("shadowJar"), "gametestClasses")
    doLast {
        val matrixId = requiredMatrixProperty("R7", "mpmt.acceptance.matrix")
        if (matrixId != "R7") throw GradleException("该任务仅支持 MATRIX R7：$matrixId")
        val runId = requiredMatrixProperty(matrixId, "mpmt.acceptance.runId")
        requiredMatrixProperty(matrixId, "mpmt.acceptance.startEpochMs")
        val report = acceptanceReportFile.get().asFile
        val serverTask = tasks.named<JavaExec>("runAcceptanceServer").get()
        val clientTask = tasks.named<JavaExec>("runAcceptanceClient").get()
        configureAcceptanceServer(serverTask)
        configureAcceptanceClient(clientTask)
        prepareAcceptanceServerProperties()
        val logDir = layout.buildDirectory.dir("acceptance").get().asFile
        var server: Process? = null
        var client: Process? = null
        try {
            server = launchAcceptanceProcess(serverTask, File(logDir, "r7-server.log"), file("run"))
            awaitAcceptancePort(server, File(logDir, "r7-server.log"), 25571)
            client = launchAcceptanceProcess(clientTask, File(logDir, "r7-client.log"), file("run-client"))
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(660_000)
            while (System.nanoTime() < deadline) {
                if (report.isFile && report.readText().contains("RUN_ID\t$runId")) break
                Thread.sleep(500)
            }
            if (!report.isFile || !report.readText().contains("RUN_ID\t$runId")) {
                throw GradleException("[realserver] R7 未在截止前生成当前运行报告：${report.absolutePath}")
            }
            verifyMatrixReport(report, matrixId)
            logger.lifecycle("[realserver] Fabric 26.2 R7 报告 PASS：${report.absolutePath}")
        } finally {
            stopAcceptanceProcess(client)
            stopAcceptanceProcess(server)
        }
    }
}

// realserver 验收门禁：严格校验 acceptance v2 + P1 REAL_REQUIRED 全 PASS（ADR-0014）。
// 实跑：① runAcceptanceServer ② runAcceptanceClient（须显示）③ 本任务读报告。
tasks.register("runRealServerAcceptance") {
    group = "verification"
    description =
        "严格校验 Fabric realserver acceptance v2 报告（默认 P1；-Pmpmt.acceptance.matrix=Rn 时校验 MATRIX + 公共三场景 + RESULT PASS）"
    doLast {
        val matrixId = (project.findProperty("mpmt.acceptance.matrix") as String?)?.trim().orEmpty()
        val report =
            if (matrixId.isNotEmpty()) {
                matrixReportFile(matrixId)
            } else {
                acceptanceReportFile.get().asFile
            }
        if (!report.exists()) {
            throw GradleException(
                "未找到验收报告（先跑 runAcceptanceServer + runAcceptanceClient）：${report.absolutePath}",
            )
        }
        val text = report.readText()
        logger.lifecycle("[realserver] 服务端权威验收报告：\n$text")
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "SERVER-GAMETEST-REPORT v2") {
            throw GradleException("[realserver] 报告不是 acceptance v2")
        }
        if (matrixId.isNotEmpty()) {
            verifyMatrixReport(report, matrixId)
            logger.lifecycle("[realserver] 矩阵 $matrixId 报告 PASS：${report.absolutePath}")
            return@doLast
        }
        val metadata =
            lines.filter { it.startsWith("META ") }.associate { line ->
                val entry = line.removePrefix("META ")
                val separator = entry.indexOf('=')
                if (separator <= 0) throw GradleException("[realserver] 非法元数据行：$line")
                entry.substring(0, separator) to entry.substring(separator + 1)
            }
        val requiredMetadata =
            listOf("commit", "VERSION", "platform", "mcVersion", "serverVersion", "productJarSha256", "scenarios")
        if (requiredMetadata.any { metadata[it].isNullOrBlank() }) {
            throw GradleException("[realserver] 报告缺少 acceptance v2 必需元数据")
        }
        if (metadata["platform"] != "fabric") {
            throw GradleException("[realserver] platform 元数据必须为 fabric：${metadata["platform"]}")
        }
        if (!metadata.getValue("productJarSha256").matches(Regex("[0-9a-fA-F]{64}"))) {
            throw GradleException("[realserver] productJarSha256 元数据非法")
        }
        if (metadata["scenarios"] != realRequiredScenarios.joinToString(",")) {
            throw GradleException("[realserver] 报告场景声明不完整：${metadata["scenarios"]}")
        }
        val resultLines = lines.filter { it.startsWith("RESULT ") }
        if (resultLines != listOf("RESULT PASS") || lines.last() != "RESULT PASS") {
            throw GradleException("[realserver] 报告必须仅有一个末行 RESULT PASS")
        }
        val scenarioLines =
            lines.filter {
                it.startsWith("PASS ") || it.startsWith("FAIL ") || it.startsWith("ERROR ") || it.startsWith("SKIP ")
            }
        val scenarios = scenarioLines.associateBy { it.split(' ', limit = 3)[1] }
        if (scenarios.size != scenarioLines.size || scenarios.keys != realRequiredScenarios.toSet()) {
            throw GradleException("[realserver] 实际场景与 P1 REAL_REQUIRED 不一致：${scenarios.keys}")
        }
        if (scenarioLines.any { !it.startsWith("PASS ") }) {
            throw GradleException("[realserver] P1 场景存在非 PASS 结果")
        }
        logger.lifecycle(
            "[realserver] 验收通过 ✓ acceptance v2，${realRequiredScenarios.size} 项 REAL_REQUIRED 全部 PASS",
        )
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

// 模拟服 GameTest 一键门禁：起 headless 服跑完整 P1 回环场景，并严格校验 acceptance v2 元数据与场景清单。
tasks.register("runSimNetworkAcceptance") {
    group = "verification"
    description = "起 headless 服跑完整 P1 模拟服场景并严格校验 acceptance v2 报告"
    dependsOn("runSimNetworkTest")
    doLast {
        val report = simReportFile.get().asFile
        if (!report.exists()) {
            throw GradleException("未找到模拟服报告（runSimNetworkTest 未写出）：${report.absolutePath}")
        }
        val text = report.readText()
        logger.lifecycle("[sim] 模拟服 GameTest 权威报告：\n$text")
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "SERVER-GAMETEST-REPORT v2") {
            throw GradleException("[sim] 模拟服报告不是 acceptance v2")
        }
        val metadata =
            lines.filter { it.startsWith("META ") }.associate { line ->
                val entry = line.removePrefix("META ")
                val separator = entry.indexOf('=')
                if (separator <= 0) throw GradleException("[sim] 非法元数据行：$line")
                entry.substring(0, separator) to entry.substring(separator + 1)
            }
        val requiredMetadata =
            listOf("commit", "VERSION", "platform", "mcVersion", "serverVersion", "productJarSha256", "scenarios")
        if (requiredMetadata.any { metadata[it].isNullOrBlank() }) {
            throw GradleException("[sim] 模拟服报告缺少 acceptance v2 必需元数据")
        }
        if (metadata["platform"] != "sim-fabric") {
            throw GradleException("[sim] platform 元数据必须为 sim-fabric：${metadata["platform"]}")
        }
        if (!metadata.getValue("productJarSha256").matches(Regex("[0-9a-fA-F]{64}"))) {
            throw GradleException("[sim] productJarSha256 元数据非法")
        }
        if (metadata["scenarios"] != simRequiredScenarios.joinToString(",")) {
            throw GradleException("[sim] 报告场景声明不完整：${metadata["scenarios"]}")
        }
        val resultLines = lines.filter { it.startsWith("RESULT ") }
        if (resultLines != listOf("RESULT PASS") || lines.last() != "RESULT PASS") {
            throw GradleException("[sim] 模拟服报告必须仅有一个末行 RESULT PASS")
        }
        val scenarioLines = lines.filter { it.startsWith("PASS ") || it.startsWith("FAIL ") || it.startsWith("ERROR ") || it.startsWith("SKIP ") }
        val scenarios = scenarioLines.associateBy { it.split(' ', limit = 3)[1] }
        if (scenarios.size != scenarioLines.size || scenarios.keys != simRequiredScenarios.toSet()) {
            throw GradleException("[sim] 实际场景与 P1 清单不一致：${scenarios.keys}")
        }
        if (scenarioLines.any { !it.startsWith("PASS ") }) {
            throw GradleException("[sim] P1 场景存在非 PASS 结果")
        }
        logger.lifecycle("[sim] 模拟服 GameTest 通过：acceptance v2，${simRequiredScenarios.size} 项 P1 场景全部 PASS")
    }
}

// gametest 测试 mod 的 fabric.mod.json 同样注入版本号占位
tasks.named<ProcessResources>("processGametestResources") {
    inputs.properties(metadataValues)
    filesMatching("fabric.mod.json") {
        expand(metadataValues)
    }
}
