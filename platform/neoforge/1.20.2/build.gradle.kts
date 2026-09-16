import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.jvm.tasks.ProcessResources
import java.security.MessageDigest
import java.util.zip.ZipFile

// platform-neoforge（L3）：根构建子模块，应用 arch-loom（top.wcpe.loom，ADR-0007，隔离加载器专属插件）。
// 锚点 MC 1.20.2（NeoForge 无 1.20.1；PRD §7）。NeoForge 运行期用官方 Mojmap（arch-loom usesMojangAtRuntime
// 对 neoforge 平台恒真 → remapJar 恒等重映射、无 SRG，区别于 Forge）；Mixin 内置（mods.toml [[mixins]] 声明、
// 无 refmap）。打包链路（ADR-0012）：shade 共享核心 + relocate snakeyaml → remapJar 产出最终产品 jar。
// dev run classpath 墙同 FG：受控 JAR 不会自动进入 dev mod 运行期类路径，经 coreLibJar（FMLModType:GAMELIBRARY）
// 放 run-*/mods 暴露。

plugins {
    `java-library`
    id("top.wcpe.loom")
    // 8.3.11：修复 RelocatorRemapper.mapValue 与 loom 依赖树上新 ASM（visitLdcInsn 传 Type）的不兼容
    id("com.gradleup.shadow") version "8.3.11"
    // 静态分析 / 质量工具链由根构建 subprojects{} 统一提供（含 spotbugs/ktlint/detekt/kover，见 ADR-0026）。
    // 车道内重复声明会分裂插件类加载器并破坏 loom 清单服务，故此处不再声明。
    // 历史说明：静态分析 / 质量工具链（严格门禁，static-analysis.md）：与根构建同一套，共享仓库根 config/ 规则集。
    // 核心 Gradle 插件经 apply(plugin=...) 接入（见下方装配块）；外部插件在此带版本直接 apply。
}

group = "top.wcpe.mc.mpmt"

val neoforgeVersion = "20.2.93"
val snakeyamlVersion = "2.2"

// 共享核心 / 平台 API / 验收核心产物：直接消费同根构建下各子模块的 jar 任务产物
// （FileCollection，自带任务依赖；文件输入、不引入传递依赖）。
// 用 withType<Jar>().matching 惰性取任务：named("jar") 会在本项目先于生产者配置时
// 立即抛 UnknownTaskException（子模块按路径序配置，:platform:neoforge:1.20.2 先于
// :platform:neoforge:neoforge-api），故不可用。
fun moduleJar(projectPath: String): FileCollection =
    files(project(projectPath).tasks.withType<Jar>().matching { it.name == "jar" })

val domainJar = moduleJar(":core:domain")
val runtimeJar = moduleJar(":core:runtime")
val protocolJar = moduleJar(":core:protocol")
val spiJar = moduleJar(":core:spi")
val serverJar = moduleJar(":core:server")
val clientJar = moduleJar(":core:client")
val neoforgeApiJar = moduleJar(":platform:neoforge:neoforge-api")
val acceptanceCoreJar = moduleJar(":modules:acceptance")
val productInternalJars =
    listOf(domainJar, runtimeJar, protocolJar, spiJar, serverJar, clientJar, neoforgeApiJar)

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
// 静态分析 / 质量工具链装配（严格门禁，static-analysis.md）——根构建子模块直接 apply。
// 共享规则集在仓库根 config/，故引用 rootProject.file("config/*")；
// .editorconfig / lombok.config 在仓库根，ktlint / Lombok 自动向上查找，无需额外配置。
// 违规即失败构建（isIgnoreFailures=false），与根构建口径一致。
// ============================================================================
// 样式审查：Checkstyle（共享裁剪规则集）
apply(plugin = "checkstyle")
configure<CheckstyleExtension> {
    toolVersion = "10.17.0"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}
// 代码异味 / 源码规则：PMD（共享裁剪规则集）
apply(plugin = "pmd")
configure<PmdExtension> {
    toolVersion = "7.0.0"
    isConsoleOutput = true
    ruleSetConfig = resources.text.fromFile(rootProject.file("config/pmd/ruleset.xml"))
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
    excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
}
dependencies.add("spotbugsPlugins", "com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
// lombok.config 由根构建 subprojects{} 统一登记为编译输入（ADR-0026），此处不再重复。
// 分析任务固定 JDK 17 启动器：Checkstyle 10.x / PMD 7.x 需 JDK 11+；本工程编译目标已是 JDK 17，
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
    // userdev 单坐标拆分（arch-loom 三段式）：原版 MC 本体 + 官方 Mojang 映射（ADR-0016）+ NeoForge
    // （arch-loom 的 neoForge 配置，由其 installer-tools 管线解析 userdev 并产出 patched MC dev jar）
    minecraft("com.mojang:minecraft:1.20.2")
    mappings(loom.officialMojangMappings())
    "neoForge"("net.neoforged:neoforge:$neoforgeVersion")

    // 文件输入没有 POM 传递关系，故显式列出完整内部闭包并一并 shade。
    productInternalJars.forEach {
        implementation(it)
        shadowBundle(it)
    }
    // 第三方运行期依赖：shade 并 relocate（ADR-0012）
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    shadowBundle("org.yaml:snakeyaml:$snakeyamlVersion")

    // dev run 运行期类路径见上方 loom runs 段（source(main) 提供 dev mod 类路径，core 库经 coreLibJar 进 run-*/mods）。

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(acceptanceCoreJar)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ============================================================================
// loom 配置：dev run（arch-loom 自动装配 MC + NeoForge dev 资源与启动类路径）
// ============================================================================
loom {
    runs {
        // loom 已为 forge 系预建默认 client/server 运行配置（client()/server() 模板已应用），这里只做等价移植。
        // source(main) 等价 NeoGradle modSource：MOD_CLASSES 指向 main 源集输出，提供产品 main 类；
        // core 库 FML 模块层不向 mod 暴露（NoClassDefFoundError），故打成带 FMLModType:GAMELIBRARY 的
        // coreLibJar 放 run-*/mods，FML 当 game library 加载、对 mod 可见（research §8）。
        // 验收驱动 acceptanceJar 亦放 mods（自带 mods.toml）。
        getByName("client") {
            configName = "NeoForge Client"
            source(project.sourceSets["main"])
            runDir("run-client")
            property("forge.logging.console.level", "info")
            property("mpmt.acceptance", "true")
            property(
                "mpmt.acceptance.server",
                (project.findProperty("mpmt.acceptance.server") as String?) ?: "127.0.0.1",
            )
        }
        // loom server 模板已自带 nogui 程序参数，无需（不可）重复声明
        getByName("server") {
            configName = "NeoForge Server"
            source(project.sourceSets["main"])
            runDir("run-server")
            property("forge.logging.console.level", "info")
            property("mpmt.acceptance", "true")
            property("mpmt.acceptance.report", project.file("run-server/acceptance-report.txt").absolutePath)
            property("mpmt.acceptance.deadlineMs", "660000")
            // v2 元数据：commit 配置期取 git；productJar 供驱动算 SHA（对齐 Forge realserver）
            property(
                "mpmt.acceptance.commit",
                providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim(),
            )
            property("mpmt.acceptance.version", project.version.toString())
            property("mpmt.acceptance.platform", "neoforge")
            property("mpmt.acceptance.mcVersion", "1.20.2")
            property("mpmt.acceptance.serverVersion", neoforgeVersion)
            property(
                "mpmt.acceptance.productJar",
                // 与 archivesName=mpmt-neoforge-1.20.2 对齐
                layout.buildDirectory
                    .file("libs/mpmt-neoforge-1.20.2-${project.version}.jar")
                    .get()
                    .asFile
                    .absolutePath,
            )
        }
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

// 普通 jar：loom 约定产物落 build/devlibs 且带 -dev 分类器（用户开发版，非最终产品）
tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

// 打包链路：shadowJar（shade core/spi + relocate snakeyaml，中间产物名 -dev-shadow）→ remapJar。
// NeoForge 1.20.2 生产运行期即 Mojang 命名（arch-loom usesMojangAtRuntime 恒真）→ 恒等重映射，
// remapJar 产出无 classifier 的最终产品 jar（承担 userdev 时代 shadowJar 的产品语义与输出路径）。
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("dev-shadow")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    configurations = listOf(shadowBundle)
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑、不缓存（与其它平台一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// remapJar 改吃 shadowJar 产物，使 core / 第三方随之进入最终产品 jar（恒等映射不改动内容，仅落位产品命名）
tasks.named<RemapJarTask>("remapJar") {
    dependsOn(tasks.named("shadowJar"))
    inputFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
    archiveClassifier.set("")
}

// 打包校验：最终产品必须是无 classifier 的 remapJar 产物（内容为 shadowJar 的恒等重映射），
// 并包含运行所需核心与平台元数据。
val verifyPackaging by tasks.registering {
    group = "verification"
    description = "校验 NeoForge mod jar：核心 shade、snakeyaml relocate、mods.toml/services 在位、未打入 Minecraft"
    dependsOn(tasks.named("remapJar"))
    doLast {
        val shadow = tasks.named<ShadowJar>("shadowJar").get()
        val plain = tasks.named<Jar>("jar").get()
        val jar = tasks.named<RemapJarTask>("remapJar").get().archiveFile.get().asFile
        val entries = ZipFile(jar).use { zf -> zf.entries().asSequence().map { it.name }.toList() }

        fun must(condition: Boolean, message: String) {
            if (!condition) throw GradleException("NeoForge 打包校验失败：$message")
        }
        must(plain.archiveFile.get().asFile != jar, "普通 jar 与最终产品 jar 输出路径冲突")
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
// 编译期继承 main 的类路径（含 arch-loom 提供的 patched MC + NeoForge API）+ main 产物，并叠加 acceptance 核心 + protocol。
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
    description = "运行 NeoForge acceptance v2 与完整默认轨场景契约测试"
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
    description = "运行 NeoForge 1.20.2 完整默认轨模拟服套件并生成 acceptance v2 报告"
    classpath = acceptance.runtimeClasspath
    mainClass.set("top.wcpe.mc.mpmt.platform.neoforge.acceptance.sim.NeoForgeDefaultSimulation")
    dependsOn(tasks.named("acceptanceClasses"), tasks.named("remapJar"))
    systemProperty("mpmt.acceptance.report", simAcceptanceReport.get().asFile.absolutePath)
    systemProperty("mpmt.acceptance.version", project.version.toString())
    systemProperty("mpmt.acceptance.platform", "neoforge")
    systemProperty("mpmt.acceptance.mcVersion", "1.20.2")
    systemProperty("mpmt.acceptance.serverVersion", neoforgeVersion)
    doFirst {
        val product = tasks.named<RemapJarTask>("remapJar").get().archiveFile.get().asFile
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
    mainClass.set("top.wcpe.mc.mpmt.platform.neoforge.acceptance.sim.NeoForgeDefaultSimulation")
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
