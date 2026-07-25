import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.spongepowered.gradle.plugin.config.PluginLoaders
import org.spongepowered.plugin.metadata.model.PluginDependency
import java.util.zip.ZipFile

// platform-sponge（L3）：独立 includeBuild，应用 SpongeGradle（ADR-0007，隔离加载器专属插件）。
// 锚点 MC 1.20.1 / SpongeAPI 11.0.0（SpongeVanilla）。Sponge 为纯服务端平台（无客户端插件 API）：
// FR-27 跨端 HUD 由 Sponge 服下发、客户端复用我方 Fabric 伴侣渲染（异构互通，同 Bukkit 模式）。
// spongeapi 由 sponge{} apiVersion 接入（compileOnly，运行期服务端提供，不 shade）；core shade + relocate snakeyaml（ADR-0012）。
// 无 reobf（Sponge 不 remap，同 NeoForge）。sponge{} DSL 生成插件元数据，不手写 sponge_plugins.json。

plugins {
    `java-library`
    id("org.spongepowered.gradle.plugin") version "2.3.0"
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

val snakeyamlVersion = "2.2"
// 插件元数据保持与 RC1365 清单一致，编译类路径固定到同源旧 API 制品。
val spongeMetadataVersion = "11.0.0-SNAPSHOT"
val spongeCompileVersion = "11.0.0-20230826.165715-4"
// 官方旧 API SHA-256：1278386c819b2009d69241e3b9356b44c3be247e7da7ea21be42aceb444459e3
// 依赖 platform-spi（经 api 传递 core-runtime + core-domain），经 includeBuild 依赖替换消费
val platformApiCoordinate = "top.wcpe.mc.mpmt:sponge-api:$version"
val spiCoordinate = "top.wcpe.mc.mpmt:spi:$version"
// 服务端公共网络特性（经 api 传递 protocol + core-runtime）
val serverCoordinate = "top.wcpe.mc.mpmt:server:$version"
// 客户端公共网络特性（握手 / 心跳），仅验收契约复用
val clientCoordinate = "top.wcpe.mc.mpmt:client:$version"

base {
    // 单锚点 1.20.1；产物名带版本
    archivesName.set("mpmt-sponge-1.20.1")
}

// 单版本构建内：common / server 分目录（Sponge 无客户端插件 API）
sourceSets.named("main") {
    java.setSrcDirs(
        listOf(
            "common/src/main/java",
            "server/src/main/java",
        ),
    )
    resources.setSrcDirs(listOf("common/src/main/resources"))
}

java {
    // RC1365 使用 Java 17，编译工具链与目标服务端保持一致。
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
}

// SpongeGradle 仍以元数据版本声明依赖，仅将精确匹配的编译依赖固定到 RC1365 同源制品。
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (
            requested.group == "org.spongepowered" &&
            requested.name == "spongeapi" &&
            requested.version == spongeMetadataVersion
        ) {
            useVersion(spongeCompileVersion)
        }
    }
}

// ============================================================================
// 静态分析 / 质量工具链装配（严格门禁，static-analysis.md）——本独立 includeBuild 单工程直接 apply。
// includeBuild 的 rootProject 即本目录，共享规则集在仓库根 config/，故引用 ../config/*；
// .editorconfig / lombok.config 在仓库根，ktlint / Lombok 自动向上查找，无需额外配置。
// 违规即失败构建（isIgnoreFailures=false），与根构建口径一致。
// 注：本工程主工具链与分析工具均使用 JDK 17。
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
// 分析任务固定 JDK 17 启动器：Checkstyle 10.x / PMD 7.x 需 JDK 11+，与平台工具链及根构建口径一致。
// SpotBugs worker 使用守护 JVM，无 javaLauncher 属性，不单独设置。
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
    // 共享核心（platform-spi + 传递 core-runtime/core-domain）：纯 Java、shade 进插件 jar
    implementation(platformApiCoordinate)
    implementation(spiCoordinate)
    shadowBundle(platformApiCoordinate)
    shadowBundle(spiCoordinate)
    // 服务端公共网络特性（FR-19）：纯 Java、shade 进插件 jar（传递 protocol）
    implementation(serverCoordinate)
    shadowBundle(serverCoordinate)
    // 第三方运行期依赖：shade 并 relocate（ADR-0012）
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    shadowBundle("org.yaml:snakeyaml:$snakeyamlVersion")
    // 注：spongeapi 由 sponge{} apiVersion 自动接入（compileOnly），不在此手动声明、不 shade

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.spongepowered:spongeapi:$spongeMetadataVersion")
}

// 插件元数据由 SpongeGradle 生成（不手写 META-INF/sponge_plugins.json）
sponge {
    // 插件元数据声明 RC1365 清单版本，时间戳版本仅用于固定编译类路径。
    apiVersion(spongeMetadataVersion)
    license("MIT")
    loader {
        name(PluginLoaders.JAVA_PLAIN)
        version("1.0")
    }
    plugin("mpmt") {
        displayName("MultiPlatformModTemplate")
        entrypoint("top.wcpe.mc.mpmt.platform.sponge.MpmtSpongePlugin")
        description("多平台 mod 玩法脚手架 —— Sponge 平台胶水")
        dependency("spongeapi") {
            loadOrder(PluginDependency.LoadOrder.AFTER)
            optional(false)
        }
    }
}

// SpongeGradle 把元数据写到 build/generated/sponge/plugin；须并入 jar（processResources 默认不带）
val spongeGeneratedMetadata = layout.buildDirectory.dir("generated/sponge/plugin")
tasks.named<ProcessResources>("processResources") {
    from(spongeGeneratedMetadata)
}
tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
    from(spongeGeneratedMetadata)
}
// 最终插件 jar = shadowJar（shade core/spi + relocate snakeyaml）。Sponge 运行期不 remap、无 reobf。
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    configurations = listOf(shadowBundle)
    from(spongeGeneratedMetadata)
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑、不缓存（与其它平台一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// 打包校验：最终插件必须与普通薄包分离，并完整包含核心、SPI、元数据和已 relocate 依赖。
val verifyPackaging by tasks.registering {
    group = "verification"
    description = "校验 Sponge 插件 jar：最终产品自包含、输出不冲突、未打入 SpongeAPI"
    dependsOn(tasks.named("shadowJar"))
    doLast {
        val shadow = tasks.named<ShadowJar>("shadowJar").get()
        val plain = tasks.named<Jar>("jar").get()
        val jar = shadow.archiveFile.get().asFile
        val entries = ZipFile(jar).use { zf -> zf.entries().asSequence().map { it.name }.toList() }

        fun must(cond: Boolean, msg: String) {
            if (!cond) throw GradleException("Sponge 打包校验失败：$msg")
        }
        must(plain.archiveFile.get().asFile != jar, "普通 jar 与最终 shadowJar 输出路径冲突")
        must(!shadow.isPreserveFileTimestamps, "最终产品仍保留源文件时间戳，无法确定性构建")
        must(shadow.isReproducibleFileOrder, "最终产品未启用可复现文件顺序")
        must(entries.contains("top/wcpe/mc/mpmt/core/domain/Mpmt.class"), "核心类未 shade 进插件 jar")
        must(entries.contains("top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class"), "platform-spi 未 shade 进插件 jar")
        must(entries.contains("top/wcpe/mc/mpmt/platform/sponge/MpmtSpongePlugin.class"), "缺少插件主类")
        must(entries.any { it.startsWith("top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/") }, "snakeyaml 未 relocate 到 libs.*")
        must(entries.none { it.startsWith("org/yaml/snakeyaml/") }, "snakeyaml 原包名残留")
        must(entries.none { it.startsWith("META-INF/maven/org.yaml/") }, "snakeyaml Maven 元数据残留")
        must(entries.contains("META-INF/sponge_plugins.json"), "缺少 META-INF/sponge_plugins.json")
        must(entries.contains("META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap"), "缺少 SPI services 声明")
        must(entries.none { it.startsWith("org/spongepowered/api/") }, "误把 SpongeAPI 打入插件 jar（应由服务端提供）")
    }
}

// ============================================================================
// realserver 验收驱动（独立 acceptance 源集 + 独立插件 jar，ADR-0014）
// Sponge 无 GameTest，故 realserver 是其唯一实机验收形态；验收驱动代码不入产品插件 jar：
// 单独打 mpmt-acceptance Sponge 插件，仅在验收运行期放入服务端。客户端复用我方 Fabric 验收伴侣（异构互通）。
// 编译期继承 main 类路径（含 spongeapi + spi/server）+ main 产物，叠加 acceptance 核心 + protocol。
// ============================================================================
val acceptanceCoordinate = "top.wcpe.mc.mpmt:acceptance:$version"
val protocolCoordinate = "top.wcpe.mc.mpmt:protocol:$version"

val acceptance: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["main"].output
}
configurations["acceptanceImplementation"].extendsFrom(configurations["implementation"])

// 纯 JVM 验收契约源集：验证 P1 清单与 acceptance v2 严格报告
val acceptanceTest: SourceSet by sourceSets.creating {
    compileClasspath += acceptance.output + acceptance.compileClasspath + sourceSets["main"].output
    runtimeClasspath += output + acceptance.runtimeClasspath + compileClasspath
}
configurations["acceptanceTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["acceptanceTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

// 专用配置：需 shade 进验收插件 jar 的内容——acceptance 核心 + protocol（+ core-domain 传递）。
// Sponge 插件类加载器隔离，故验收 jar 自包含（同 Bukkit），不依赖产品 jar 提供这些类。
val acceptanceShadowBundle: Configuration by configurations.creating

dependencies {
    "acceptanceImplementation"(acceptanceCoordinate)
    "acceptanceImplementation"(protocolCoordinate)
    acceptanceShadowBundle(acceptanceCoordinate)
    acceptanceShadowBundle(protocolCoordinate)
    // 纯 JVM P1 契约依赖仅挂在 acceptanceTest，避免验收插件 jar 膨胀
    "acceptanceTestImplementation"(acceptanceCoordinate)
    "acceptanceTestImplementation"(protocolCoordinate)
    "acceptanceTestImplementation"(serverCoordinate)
    "acceptanceTestImplementation"(clientCoordinate)
    "acceptanceTestImplementation"(platform("org.junit:junit-bom:5.10.3"))
    "acceptanceTestImplementation"("org.junit.jupiter:junit-jupiter")
    "acceptanceTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

// 验收插件 sponge_plugins.json 的 ${version} 占位由构建注入
tasks.named<ProcessResources>("processAcceptanceResources") {
    inputs.property("version", project.version)
    filesMatching("META-INF/sponge_plugins.json") {
        expand("version" to project.version)
    }
}

// 验收驱动插件 jar：shade acceptance 核心 + protocol + core-domain（均第一方、无第三方运行期依赖，无需 relocate）
val acceptanceJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "构建 realserver 验收驱动插件 mpmt-acceptance（仅验收运行期用，不入产品 jar）"
    archiveBaseName.set("mpmt-acceptance")
    archiveClassifier.set("")
    from(acceptance.output)
    configurations = listOf(acceptanceShadowBundle)
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑（与产品 shadowJar 一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

tasks.named("assemble") {
    dependsOn(verifyPackaging)
}

val acceptanceContractTest by tasks.registering(Test::class) {
    group = "verification"
    description = "运行 Sponge acceptance v2 与完整 P1 场景契约测试"
    testClassesDirs = acceptanceTest.output.classesDirs
    classpath = acceptanceTest.runtimeClasspath
    useJUnitPlatform()
}

// 把验收源集与契约测试纳入常规 build/check
tasks.named("build") {
    dependsOn(tasks.named("acceptanceClasses"), acceptanceContractTest)
}
tasks.named("check") {
    dependsOn(acceptanceContractTest)
}

// realserver 门禁：Sponge 服 + Fabric gametest 客户端进服写报告后校验。
val spongeRealserverReport =
    providers.gradleProperty("mpmt.acceptance.report")
        .map { file(it) }
        .orElse(provider { file("run/acceptance-report.txt") })

tasks.register("runRealServerAcceptance") {
    group = "verification"
    description = "Sponge realserver 门禁：校验权威报告 RESULT PASS"
    doLast {
        val report = spongeRealserverReport.get()
        if (!report.isFile) {
            throw GradleException(
                "未找到 Sponge 验收报告：${report.absolutePath}（先起 Sponge + Fabric gametest 客户端）",
            )
        }
        val lines =
            report.readText().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.lastOrNull() != "RESULT PASS") {
            throw GradleException(
                "Sponge realserver 未通过：${report.absolutePath}\n${report.readText()}",
            )
        }
        logger.lifecycle("[realserver] Sponge 报告 PASS：${report.absolutePath}")
    }
}

tasks.test {
    useJUnitPlatform()
}
