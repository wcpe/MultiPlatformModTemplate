import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.jvm.tasks.ProcessResources
import java.security.MessageDigest
import java.util.zip.ZipFile

// platform-neoforge（L3）：自有 Gradle 8 车道，应用 NeoGradle（ADR-0007，隔离加载器专属插件）。
// 锚点 MC 1.20.2（NeoForge 无 1.20.1；PRD §7）。NeoForge 运行期用官方 Mojmap、无 SRG/reobf（区别于 Forge）；
// Mixin 内置（mods.toml [[mixins]] 声明、无 MixinGradle、无 refmap）。打包：shade 共享核心 + relocate snakeyaml（ADR-0012）。
// dev run classpath 墙同 FG：受控 JAR 不会自动进入 modSource 运行期类路径，经 additionalRuntimeClasspath 暴露。

plugins {
    `java-library`
    id("net.neoforged.gradle.userdev") version "7.0.116"
    id("com.gradleup.shadow") version "8.3.3"
    // 静态分析 / 质量工具链（严格门禁，static-analysis.md）：与根构建同一套，共享仓库根 config/ 规则集。
    // 核心 Gradle 插件经 apply(plugin=...) 接入（见下方装配块）；外部插件在此带版本直接 apply。
    id("com.github.spotbugs") version "6.0.26"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
}

group = "top.wcpe.mc.mpmt"
version = file("../../../VERSION").readText().trim()

val neoforgeVersion = "20.2.93"
val snakeyamlVersion = "2.2"
// 自有 Gradle 8 车道不能反向 include 根工程；根工程先产出这些受控 JAR，
// 本车道只按文件消费，避免复合构建循环和嵌套 Gradle 调用。
val repositoryRoot = rootProject.file("../../..").canonicalFile
fun internalJar(modulePath: String, archiveName: String) =
    files(File(repositoryRoot, "$modulePath/build/libs/$archiveName-$version.jar"))

val domainJar = internalJar("core/domain", "domain")
val runtimeJar = internalJar("core/runtime", "runtime")
val protocolJar = internalJar("core/protocol", "protocol")
val spiJar = internalJar("core/spi", "spi")
val serverJar = internalJar("core/server", "server")
val clientJar = internalJar("core/client", "client")
val neoforgeApiJar = internalJar("platform/neoforge/neoforge-api", "platform-neoforge-api")
val acceptanceCoreJar = internalJar("modules/acceptance", "acceptance")
val productInternalJars: List<FileCollection> =
    listOf(domainJar, runtimeJar, protocolJar, spiJar, serverJar, clientJar, neoforgeApiJar)
val requiredInternalJars: List<FileCollection> = productInternalJars + listOf(acceptanceCoreJar)

base {
    // 单锚点 1.20.2；产物名带版本以免与多版本矩阵混淆
    archivesName.set("mpmt-neoforge-1.20.2")
}

// 单版本构建内：common / server / client 分目录（服客分离）
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

java {
    // NeoForge 1.20.2 运行于 Java 17
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
}

// ============================================================================
// 静态分析 / 质量工具链装配（严格门禁，static-analysis.md）——本独立 includeBuild 单工程直接 apply。
// 本独立车道的 rootProject 即本目录，共享规则集在仓库根 config/，故引用 ../../../config/*；
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
    toolVersion = "7.0.0"
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
// 分析任务固定 JDK 17 启动器：Checkstyle 10.x / PMD 7.x 需 JDK 11+；本工程 NeoGradle 编译目标已是 JDK 17，
// 仍显式固定分析任务启动器与根构建口径一致。SpotBugs worker 用守护 JVM，无 javaLauncher 属性、不设。
val analysisToolchains = extensions.getByType(JavaToolchainService::class.java)
val analysisLauncher =
    analysisToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
tasks.withType(Checkstyle::class.java).configureEach {
    javaLauncher.set(analysisLauncher)
}
tasks.withType(Pmd::class.java).configureEach {
    javaLauncher.set(analysisLauncher)
}
// 仅生产码（spotbugsMain）严格门禁；test / acceptance 等非 main 源集宽松
// （测试与验收 harness 常含 mock/反射等 SpotBugs 噪声，安全/缺陷分析重在生产码）。
tasks.withType(SpotBugsTask::class.java).configureEach {
    if (name != "spotbugsMain") {
        ignoreFailures = true
    }
}

// 专用配置：需 shade 进产物并 relocate 的内容（core/spi + 第三方运行期依赖）
val shadowBundle: Configuration by configurations.creating

dependencies {
    // NeoForge userdev：单一依赖传递性引入 patched MC + loader（NeoGradle 7，非 minecraft(...)）
    implementation("net.neoforged:neoforge:$neoforgeVersion")

    // 文件输入没有 POM 传递关系，故显式列出完整内部闭包并一并 shade。
    productInternalJars.forEach {
        implementation(it)
        shadowBundle(it)
    }
    // 第三方运行期依赖：shade 并 relocate（ADR-0012）
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    shadowBundle("org.yaml:snakeyaml:$snakeyamlVersion")

    // dev run 运行期类路径见文件末尾 realserver 编排段（先实测 modSource 是否已含库 runtimeClasspath）。

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(acceptanceCoreJar)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val verifyInternalJars by tasks.registering {
    group = "verification"
    description = "校验 NeoForge 1.20.2 受控内部 JAR 输入已由根工程准备"
    doLast {
        val missing = requiredInternalJars.flatMap { it.files }.filterNot(File::isFile)
        if (missing.isNotEmpty()) {
            val paths = missing.joinToString(System.lineSeparator()) { "  - ${it.absolutePath}" }
            throw GradleException(
                "缺少 NeoForge 1.20.2 内部 JAR 输入：${System.lineSeparator()}$paths${System.lineSeparator()}" +
                    "请先在仓库根运行 ./gradlew :prepareNeoForge1202Inputs；不要反向 includeBuild 或嵌套调用 Gradle。",
            )
        }
    }
}
tasks.withType<JavaCompile>().configureEach {
    dependsOn(verifyInternalJars)
}

// NeoGradle 运行配置：client/server dev run（NeoGradle 自动装好 MC 客户端 + 资源）。
// Kotlin DSL 下 runs 为 NamedDomainObjectContainer，用 create("...")（Groovy 的 client{} 简写不可用）。
runs {
    configureEach {
        // modSource 必需（NeoGradle 装配 BootstrapLauncher 启动类路径），提供产品 main 类；core 库 FML 模块层
        // 不向 mod 暴露（NoClassDefFoundError），故打成带 FMLModType:GAMELIBRARY 的 coreLibJar 放 run-*/mods，
        // FML 当 game library 加载、对 mod 可见（research §8）。验收驱动 acceptanceJar 亦放 mods（自带 mods.toml）。
        modSource(project.sourceSets["main"])
        systemProperty("forge.logging.console.level", "info")
        systemProperty("mpmt.acceptance", "true")
    }
    create("client") {
        workingDirectory(project.file("run-client"))
        systemProperty(
            "mpmt.acceptance.server",
            (project.findProperty("mpmt.acceptance.server") as String?) ?: "127.0.0.1",
        )
    }
    create("server") {
        workingDirectory(project.file("run-server"))
        programArgument("--nogui")
        systemProperty(
            "mpmt.acceptance.report",
            project.file("run-server/acceptance-report.txt").absolutePath,
        )
        systemProperty("mpmt.acceptance.deadlineMs", "660000")
        // v2 元数据：commit 配置期取 git；productJar 供驱动算 SHA（对齐 Forge realserver）
        systemProperty(
            "mpmt.acceptance.commit",
            providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim(),
        )
        systemProperty("mpmt.acceptance.version", project.version.toString())
        systemProperty("mpmt.acceptance.platform", "neoforge")
        systemProperty("mpmt.acceptance.mcVersion", "1.20.2")
        systemProperty("mpmt.acceptance.serverVersion", neoforgeVersion)
        systemProperty(
            "mpmt.acceptance.productJar",
            layout.buildDirectory
                .file("libs/mpmt-neoforge-1.20.2-${project.version}.jar")
                .get()
                .asFile
                .absolutePath,
        )
    }
}

// dev run 用 core 库 jar：shade core/spi（含传递 protocol/core-domain）+ relocate snakeyaml，带
// FMLModType:GAMELIBRARY manifest，放 run-*/mods 让 FML 当 game library 暴露给 mod（绕 dev classpath 墙）。
// 仅 dev run 用、不发布、不入产品 mod jar。
val coreLibJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "dev run 用 core 库 jar（FMLModType:GAMELIBRARY，放 run-*/mods 绕 dev classpath 墙）"
    archiveBaseName.set("mpmt-neoforge-corelib")
    archiveClassifier.set("")
    configurations = listOf(shadowBundle)
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    exclude("META-INF/maven/**")
    manifest { attributes("FMLModType" to "GAMELIBRARY") }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// mods.toml 的 ${version} 占位由构建注入
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/mods.toml") {
        expand("version" to project.version)
    }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

// 最终 mod jar = shadowJar（shade core/spi + relocate snakeyaml）。NeoForge 运行期 Mojmap、无 reobf。
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    configurations = listOf(shadowBundle)
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑、不缓存（与其它平台一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// 打包校验：最终产品必须是无 classifier 的 shadowJar，并包含运行所需核心与平台元数据。
val verifyPackaging by tasks.registering {
    group = "verification"
    description = "校验 NeoForge mod jar：核心 shade、snakeyaml relocate、mods.toml/services 在位、未打入 Minecraft"
    dependsOn(tasks.named("shadowJar"))
    doLast {
        val shadow = tasks.named<ShadowJar>("shadowJar").get()
        val plain = tasks.named<Jar>("jar").get()
        val jar = shadow.archiveFile.get().asFile
        val entries = ZipFile(jar).use { zf -> zf.entries().asSequence().map { it.name }.toList() }

        fun must(condition: Boolean, message: String) {
            if (!condition) throw GradleException("NeoForge 打包校验失败：$message")
        }
        must(plain.archiveFile.get().asFile != jar, "普通 jar 与最终 shadowJar 输出路径冲突")
        must(!shadow.isPreserveFileTimestamps, "最终产品仍保留源文件时间戳，无法确定性构建")
        must(shadow.isReproducibleFileOrder, "最终产品未启用可复现文件顺序")
        must(entries.contains("top/wcpe/mc/mpmt/core/domain/Mpmt.class"), "核心类未 shade 进 mod jar")
        must(entries.contains("top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class"), "platform-spi 未 shade 进 mod jar")
        must(entries.contains("top/wcpe/mc/mpmt/platform/neoforge/MpmtNeoForgeMod.class"), "缺少 NeoForge mod 主类")
        must(entries.any { it.startsWith("top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/") }, "snakeyaml 未 relocate 到 libs.*")
        must(entries.none { it.startsWith("org/yaml/snakeyaml/") }, "snakeyaml 原包名残留")
        must(entries.none { it.startsWith("META-INF/maven/org.yaml/") }, "snakeyaml Maven 元数据残留")
        must(entries.contains("META-INF/mods.toml"), "缺少 META-INF/mods.toml")
        must(entries.contains("META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap"), "缺少 SPI services 声明")
        must(entries.none { it.startsWith("net/minecraft/") }, "误把 Minecraft 类打入 mod jar")
        println("NeoForge 打包校验通过：")
        println("  产物 = ${jar.name}（条目数 ${entries.size}）")
        println("  核心已 shade、snakeyaml 已 relocate、mods.toml/services 在位、未打入 Minecraft")
    }
}

tasks.named("assemble") {
    dependsOn(verifyPackaging)
}

tasks.register("packageArtifacts") {
    group = "build"
    description = "构建 NeoForge 产品与 realserver 验收产物"
    dependsOn(verifyPackaging, "acceptanceJar")
}

tasks.test {
    useJUnitPlatform()
}

// ============================================================================
// realserver 验收驱动（独立 acceptance 源集 + 独立 shaded mod jar，ADR-0014）
// NeoForge 与 Forge 同走 realserver：真实 NeoForge 专用服 + 独立 acceptance mod jar。验收驱动代码不入产品 mod jar：
// 单独打 mpmt-acceptance-neoforge mod，仅在验收运行期放入服务端 mods/。
// 编译期继承 main 的类路径（含 NeoGradle 提供的 patched MC + NeoForge API）+ main 产物，并叠加 acceptance 核心 + protocol。
// NeoForge 运行期官方 Mojmap、无 SRG/reobf（区别于 Forge），故无 reobf 步骤。
// ============================================================================
val acceptance: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["main"].output
}
configurations["acceptanceImplementation"].extendsFrom(configurations["implementation"])

val acceptanceTest: SourceSet by sourceSets.creating {
    compileClasspath += acceptance.output + sourceSets["main"].output
    runtimeClasspath += output + compileClasspath
}
configurations["acceptanceTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["acceptanceTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

// 专用配置：需 shade 进验收 mod jar 的内容——**只含 acceptance 核心**（验收 jar 独有、不在产品 jar 里）。
// protocol/core-domain 等已在产品 mod jar 内：NeoForge 的 FML 模块层禁止两个 mod 导出同名包（split package，
// 否则启动失败），故验收 jar 不能再打 protocol/core-domain；运行期由产品 mod 提供（FML mod 为自动模块，
// 验收 mod 可读取产品 mod 的包）。protocol 仅作编译期依赖（compileOnly），不入产物。
val acceptanceShadowBundle: Configuration by configurations.creating

dependencies {
    // 平台无关验收核心（控制协议 / 协调 / GameTest 框架 / 报告）：验收 jar 独有，shade 进去
    "acceptanceImplementation"(acceptanceCoreJar)
    acceptanceShadowBundle(acceptanceCoreJar)
    // protocol（编 HUD 包用）：仅编译期可见，运行期由产品 mod 提供——绝不打进验收 jar（防 split package）
    "acceptanceCompileOnly"(protocolJar)
}

// 验收 mod mods.toml 的 ${version} 占位由构建注入
tasks.named<ProcessResources>("processAcceptanceResources") {
    inputs.property("version", project.version)
    filesMatching("META-INF/mods.toml") {
        expand("version" to project.version)
    }
}

// 验收驱动 mod jar：仅 shade acceptance 核心（第一方、无第三方运行期依赖，无需 relocate）。NeoForge 运行期 Mojmap、无 reobf。
val acceptanceJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "构建 realserver 验收驱动 mod mpmt-acceptance-neoforge（仅验收运行期用，不入产品 jar）"
    archiveBaseName.set("mpmt-acceptance-neoforge")
    archiveClassifier.set("")
    from(acceptance.output)
    dependsOn(tasks.named("acceptanceClasses"))
    configurations = listOf(acceptanceShadowBundle)
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑（与产品 shadowJar 一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// 把验收源集纳入常规 build 的编译校验（只编译，不打包——打包由验收编排按需触发）
val acceptanceContractTest by tasks.registering(Test::class) {
    group = "verification"
    description = "运行 NeoForge acceptance v2 与完整 P1 场景契约测试"
    testClassesDirs = acceptanceTest.output.classesDirs
    classpath = acceptanceTest.runtimeClasspath
    useJUnitPlatform()
    dependsOn(tasks.named("acceptanceClasses"))
}

val simAcceptanceReport = layout.buildDirectory.file("acceptance/sim-report-v2.txt")
val realAcceptanceReport =
    providers.gradleProperty("mpmt.acceptance.report")
        .map { file(it) }
        .orElse(provider { file("run-server/acceptance-report.txt") })

val runSimNetworkAcceptance by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "运行 NeoForge 1.20.2 完整 P1 模拟服套件并生成 acceptance v2 报告"
    classpath = acceptance.runtimeClasspath
    mainClass.set("top.wcpe.mc.mpmt.platform.neoforge.acceptance.sim.NeoForgeP1Simulation")
    dependsOn(tasks.named("acceptanceClasses"), tasks.named("shadowJar"))
    systemProperty("mpmt.acceptance.report", simAcceptanceReport.get().asFile.absolutePath)
    systemProperty("mpmt.acceptance.version", project.version.toString())
    systemProperty("mpmt.acceptance.platform", "neoforge")
    systemProperty("mpmt.acceptance.mcVersion", "1.20.2")
    systemProperty("mpmt.acceptance.serverVersion", neoforgeVersion)
    doFirst {
        val product = tasks.named<ShadowJar>("shadowJar").get().archiveFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(product.readBytes())
        systemProperty("mpmt.acceptance.productJarSha256", digest.joinToString("") { byte -> "%02x".format(byte) })
        val commit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
        systemProperty("mpmt.acceptance.commit", commit)
    }
}

val verifyAcceptanceReport by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "严格校验 NeoForge acceptance v2 报告，缺元数据、场景或 PASS 均失败"
    classpath = acceptance.runtimeClasspath
    mainClass.set("top.wcpe.mc.mpmt.platform.neoforge.acceptance.sim.NeoForgeP1Simulation")
    dependsOn(tasks.named("acceptanceClasses"))
    doFirst {
        val report = realAcceptanceReport.get()
        if (!report.isFile) {
            throw GradleException("未找到 NeoForge 验收报告：${report.absolutePath}")
        }
        args("verify", report.absolutePath)
    }
}

// B 车道：NeoForge 专用服 + 自有 acceptance 客户端伴侣进服后读报告。
tasks.register("runRealServerAcceptance") {
    group = "verification"
    description =
        "NeoForge realserver 门禁：校验权威报告（须先专用服 + NeoForge acceptance 客户端 gametest）"
    dependsOn(verifyAcceptanceReport)
}

// 把验收源集与契约测试纳入常规 build/check，验收驱动仍不进入产品 jar。
tasks.named("build") {
    dependsOn(tasks.named("acceptanceClasses"), acceptanceContractTest)
}
tasks.named("check") {
    dependsOn(acceptanceContractTest)
}
