import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.spongepowered.gradle.plugin.config.PluginLoaders
import org.spongepowered.plugin.metadata.model.PluginDependency

// platform-sponge（L3）：独立 includeBuild，应用 SpongeGradle（ADR-0007，隔离加载器专属插件）。
// 锚点 MC 1.20.1 / SpongeAPI 11.0.0（SpongeVanilla）。Sponge 为纯服务端平台（无客户端插件 API）：
// FR-27 跨端 HUD 由 Sponge 服下发、客户端复用我方 Fabric 伴侣渲染（异构互通，同 Bukkit 模式）。
// spongeapi 由 sponge{} apiVersion 接入（compileOnly，运行期服务端提供，不 shade）；core shade + relocate snakeyaml（ADR-0012）。
// 无 reobf（Sponge 不 remap，同 NeoForge）。sponge{} DSL 生成插件元数据，不手写 sponge_plugins.json。

plugins {
    `java-library`
    id("org.spongepowered.gradle.plugin") version "2.3.0"
    id("com.gradleup.shadow") version "8.3.3"
}

group = "top.wcpe.mc.mpmt"
version = file("../VERSION").readText().trim()

val snakeyamlVersion = "2.2"
// 依赖 platform-spi（经 api 传递 core-runtime + core-domain），经 includeBuild 依赖替换消费
val spiCoordinate = "top.wcpe.mc.mpmt:platform-spi:$version"
// 服务端公共网络特性（经 api 传递 protocol + core-runtime）
val serverCoordinate = "top.wcpe.mc.mpmt:core-server:$version"

base {
    archivesName.set("mpmt-sponge")
}

java {
    // maven 上 spongeapi 11 唯二可用制品（release 11.0.0 与 SNAPSHOT 构建 50）均为 Java 21 字节码（major 65、
    // GMM 锁 JVM21），故工具链取 21 以能编译（ADR-0004）；shade 的 core 为 Java 8 字节码、与 21 兼容。
    // ⚠️ 上游制品错位（2026-06 实测）：最新可部署的 SpongeVanilla 1.20.1（RC1365）实为 **Java 17** 服、且内置
    // spongeapi 为网络「连接」模型（重构前 RawPlayDataHandler<EngineConnection>），与当前 maven API（重构后
    // 「连接状态」模型 ServerConnectionState.Game）不兼容——故 realserver 待上游放出与当前 API 同源的
    // SpongeVanilla 1.20.1 服后再实跑（详见 CHANGELOG / docs/ARCHITECTURE）。
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
}

// 专用配置：需 shade 进产物并 relocate 的内容（core/spi + 第三方运行期依赖）
val shadowBundle: Configuration by configurations.creating

dependencies {
    // 共享核心（platform-spi + 传递 core-runtime/core-domain）：纯 Java、shade 进插件 jar
    implementation(spiCoordinate)
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
}

// 插件元数据由 SpongeGradle 生成（不手写 META-INF/sponge_plugins.json）
sponge {
    // spongeapi 11 唯二可用制品 release 11.0.0 与 SNAPSHOT 构建 50 均 Java 21 字节码；取 SNAPSHOT 作编译基线
    apiVersion("11.0.0-SNAPSHOT")
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

// 最终插件 jar = shadowJar（shade core/spi + relocate snakeyaml）。Sponge 运行期不 remap、无 reobf。
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations = listOf(shadowBundle)
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑、不缓存（与其它平台一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
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

// 专用配置：需 shade 进验收插件 jar 的内容——acceptance 核心 + protocol（+ core-domain 传递）。
// Sponge 插件类加载器隔离，故验收 jar 自包含（同 Bukkit），不依赖产品 jar 提供这些类。
val acceptanceShadowBundle: Configuration by configurations.creating

dependencies {
    "acceptanceImplementation"(acceptanceCoordinate)
    "acceptanceImplementation"(protocolCoordinate)
    acceptanceShadowBundle(acceptanceCoordinate)
    acceptanceShadowBundle(protocolCoordinate)
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

// 把验收源集纳入常规 build 的编译校验（只编译，不打包——打包由验收编排按需触发）
tasks.named("build") {
    dependsOn(tasks.named("acceptanceClasses"))
}

tasks.test {
    useJUnitPlatform()
}
