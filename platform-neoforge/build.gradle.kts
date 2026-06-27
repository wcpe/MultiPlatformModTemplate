import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.language.jvm.tasks.ProcessResources

// platform-neoforge（L3）：独立 includeBuild，应用 NeoGradle（ADR-0007，隔离加载器专属插件）。
// 锚点 MC 1.20.2（NeoForge 无 1.20.1；PRD §7）。NeoForge 运行期用官方 Mojmap、无 SRG/reobf（区别于 Forge）；
// Mixin 内置（mods.toml [[mixins]] 声明、无 MixinGradle、无 refmap）。打包：shade 共享核心 + relocate snakeyaml（ADR-0012）。
// dev run classpath 墙同 FG：includeBuild/shaded 库默认不在 dev 运行期类路径，经 additionalRuntimeClasspath 暴露。

plugins {
    `java-library`
    id("net.neoforged.gradle.userdev") version "7.0.116"
    id("com.gradleup.shadow") version "8.3.3"
}

group = "top.wcpe.mc.mpmt"
version = file("../VERSION").readText().trim()

val neoforgeVersion = "20.2.93"
val snakeyamlVersion = "2.2"
// 依赖 platform-spi（经 api 传递 core-runtime + core-domain），经 includeBuild 依赖替换消费
val spiCoordinate = "top.wcpe.mc.mpmt:platform-spi:$version"
// 服务端公共网络特性（经 api 传递 protocol + core-runtime）
val serverCoordinate = "top.wcpe.mc.mpmt:core-server:$version"

base {
    archivesName.set("mpmt-neoforge")
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

// 专用配置：需 shade 进产物并 relocate 的内容（core/spi + 第三方运行期依赖）
val shadowBundle: Configuration by configurations.creating

dependencies {
    // NeoForge userdev：单一依赖传递性引入 patched MC + loader（NeoGradle 7，非 minecraft(...)）
    implementation("net.neoforged:neoforge:$neoforgeVersion")

    // 共享核心（platform-spi + 传递 core-runtime/core-domain）：纯 Java、shade 进 mod jar
    implementation(spiCoordinate)
    shadowBundle(spiCoordinate)
    // 服务端公共网络特性（FR-19）：纯 Java、shade 进 mod jar（传递 protocol）
    implementation(serverCoordinate)
    shadowBundle(serverCoordinate)
    // 第三方运行期依赖：shade 并 relocate（ADR-0012）
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    shadowBundle("org.yaml:snakeyaml:$snakeyamlVersion")

    // dev run 运行期类路径见文件末尾 realserver 编排段（先实测 modSource 是否已含库 runtimeClasspath）。

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    }
    create("server") {
        workingDirectory(project.file("run-server"))
        programArgument("--nogui")
        systemProperty(
            "mpmt.acceptance.report",
            project.file("run-server/acceptance-report.txt").absolutePath)
        systemProperty("mpmt.acceptance.deadlineMs", "660000")
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
    configurations = listOf(shadowBundle)
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑、不缓存（与其它平台一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
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
val acceptanceCoordinate = "top.wcpe.mc.mpmt:acceptance:$version"
val protocolCoordinate = "top.wcpe.mc.mpmt:protocol:$version"

val acceptance: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["main"].output
}
configurations["acceptanceImplementation"].extendsFrom(configurations["implementation"])

// 专用配置：需 shade 进验收 mod jar 的内容——**只含 acceptance 核心**（验收 jar 独有、不在产品 jar 里）。
// protocol/core-domain 等已在产品 mod jar 内：NeoForge 的 FML 模块层禁止两个 mod 导出同名包（split package，
// 否则启动失败），故验收 jar 不能再打 protocol/core-domain；运行期由产品 mod 提供（FML mod 为自动模块，
// 验收 mod 可读取产品 mod 的包）。protocol 仅作编译期依赖（compileOnly），不入产物。
val acceptanceShadowBundle: Configuration by configurations.creating

dependencies {
    // 平台无关验收核心（控制协议 / 协调 / GameTest 框架 / 报告）：验收 jar 独有，shade 进去
    "acceptanceImplementation"(acceptanceCoordinate)
    acceptanceShadowBundle(acceptanceCoordinate)
    // protocol（编 HUD 包用）：仅编译期可见，运行期由产品 mod 提供——绝不打进验收 jar（防 split package）
    "acceptanceCompileOnly"(protocolCoordinate)
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
    configurations = listOf(acceptanceShadowBundle)
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑（与产品 shadowJar 一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// 把验收源集纳入常规 build 的编译校验（只编译，不打包——打包由验收编排按需触发）
tasks.named("build") {
    dependsOn(tasks.named("acceptanceClasses"))
}
