import buildconventions.SpongeLaneExtension

// Sponge 1.20.1 车道（根构建子模块）：common + server 分目录 → mpmt-sponge-1.20.1-<version>.jar。
// 不可变契约：产物名与路径、sponge{} 生成的插件元数据（不手写 sponge_plugins.json）、core shade +
// relocate snakeyaml（ADR-0012）、SpongeAPI 坐标（编译期 spongeapi、运行期由服务端提供、不 shade）、
// 真服报告路径与判定强度（ADR-0014）。锚点 MC 1.20.1 / SpongeAPI 11.0.0（SpongeVanilla）。
// Sponge 为纯服务端平台（无客户端插件 API）：跨端 HUD 由 Sponge 服下发、客户端复用我方 Fabric 伴侣渲染。
// 元数据装配、打包链路、验收接入层与门禁由 build-conventions.sponge 承担（ADR-0027），本脚本只留参数与差异。
plugins {
    id("build-conventions.quality")
    `java-library`
    // 车道约定插件须晚于 java-library：验收源集与测试依赖挂在 java 插件之上
    id("build-conventions.sponge")
    id("org.spongepowered.gradle.plugin")
    id("com.gradleup.shadow") version "8.3.11"
    // 分析类插件（spotbugs / ktlint / detekt / kover）不在车道内声明：由 build-conventions.quality 应用，
    // 版本与类路径由根 plugins{} 单点 pin，重复声明会分裂插件类加载器并破坏 loom 的清单服务。
}

group = "top.wcpe.mc.mpmt"

val snakeyamlVersion = "2.2"
// 插件元数据保持与 RC1365 清单一致，编译类路径固定到同源旧 API 制品。
val spongeMetadataVersion = "11.0.0-SNAPSHOT"
val spongeCompileVersion = "11.0.0-20230826.165715-4"
// 官方旧 API SHA-256：1278386c819b2009d69241e3b9356b44c3be247e7da7ea21be42aceb444459e3
// 依赖 platform-spi（经 api 传递 core-runtime + core-domain），经项目依赖消费
val platformApiCoordinate = project(":platform:sponge:sponge-api")
val spiCoordinate = project(":core:spi")
// 服务端公共网络特性（经 api 传递 protocol + core-runtime）
val serverCoordinate = project(":core:server")
// 客户端公共网络特性（握手 / 心跳），仅验收契约复用
val clientCoordinate = project(":core:client")
// realserver 验收 harness 核心与协议（shade 进验收驱动插件 jar，不入产品 jar，ADR-0014）
val acceptanceCoordinate = project(":modules:acceptance")
val protocolCoordinate = project(":core:protocol")

base {
    // 单锚点 1.20.1；产物名带版本
    archivesName.set("mpmt-sponge-1.20.1")
}

// sponge 车道参数：元数据取值、目标 JDK、验收 jar 名与报告路径在此声明；
// sponge{} 元数据装配、打包链路、验收源集与契约测试接线、真服门禁均由插件承担。
val spongeLane = extensions.getByType(SpongeLaneExtension::class.java)
spongeLane.spongeApiMetadataVersion.set(spongeMetadataVersion)
spongeLane.spongeApiCompileVersion.set(spongeCompileVersion)
spongeLane.targetJavaVersion.set(17)
spongeLane.pluginId.set("mpmt")
spongeLane.displayName.set("MultiPlatformModTemplate")
spongeLane.entrypoint.set("top.wcpe.mc.mpmt.platform.sponge.MpmtSpongePlugin")
spongeLane.description.set("多平台 mod 玩法脚手架 —— Sponge 平台胶水")
spongeLane.acceptanceJarName.set("mpmt-acceptance")
spongeLane.acceptanceReport.set("run/acceptance-report.txt")

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

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
}
// 质量工具链：装配由 build-conventions.quality 插件承担（本工程无偏离项）

// 专用配置：需 shade 进产品 jar 并 relocate 的内容（core/spi + 第三方运行期依赖）
val shadowBundle: Configuration by configurations.creating
// 专用配置：需 shade 进验收插件 jar 的内容——acceptance 核心 + protocol（+ core-domain 传递）。
// Sponge 插件类加载器隔离，故验收 jar 自包含（同 Bukkit），不依赖产品 jar 提供这些类。
val acceptanceShadowBundle: Configuration by configurations.creating

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

    "acceptanceImplementation"(acceptanceCoordinate)
    "acceptanceImplementation"(protocolCoordinate)
    acceptanceShadowBundle(acceptanceCoordinate)
    acceptanceShadowBundle(protocolCoordinate)
    // 纯 JVM 默认轨契约依赖仅挂在 acceptanceTest，避免验收插件 jar 膨胀
    "acceptanceTestImplementation"(acceptanceCoordinate)
    "acceptanceTestImplementation"(protocolCoordinate)
    "acceptanceTestImplementation"(serverCoordinate)
    "acceptanceTestImplementation"(clientCoordinate)
}
