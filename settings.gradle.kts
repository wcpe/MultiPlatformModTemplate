// 根构建（docs/adr/0026-single-build-subproject-unification.md）：单一根树下 include 全部平台车道子模块。
// 不可变契约：工程路径与目录映射、插件版本单点 pin（车道脚本只写 id 不带版本）、两个约定插件工程的 includeBuild。
// 物理布局即工程路径：core/* · platform/<loader>/* · modules/*；模块坐标 = group + project.name（path 末段）。

pluginManagement {
    repositories {
        // 上游 marker 临时不可用时，仅从本机 Maven 仓库解析 mc-testkit 与 mc 坐标。
        mavenLocal {
            content {
                includeGroup("top.wcpe.mc-testkit")
                includeGroup("top.wcpe.mc")
            }
        }
        // WCPE Loom（top.wcpe.loom）发布地：dev.architectury:architectury-loom
        maven("https://maven.wcpe.top/repository/maven-releases/") { name = "WCPE Releases" }
        // 各加载器插件与运行期依赖仓库（根单点声明，车道脚本不再各自声明）
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.architectury.dev/") { name = "Architectury" }
        maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
        gradlePluginPortal()
        mavenCentral()
    }
    // 插件版本单点 pin（ADR-0025 / ADR-0026）：车道脚本只写 id，不带版本。
    plugins {
        id("top.wcpe.loom") version "1.17.1"
        // 无混淆变体：loom 的 MixinAPMappingService 遍历全构建 loom 工程，
        // 仅跳过 LoomNoRemapGradlePlugin.isApplied() 的工程；仅靠 fabric.loom.disableObfuscation=true
        // 会让该服务对无 mappings 的工程调用 getMappingConfiguration() 而抛错。
        id("top.wcpe.loom-no-remap") version "1.17.1"
        id("org.spongepowered.gradle.plugin") version "2.3.0"
    }
    // 真服验收编排约定插件（Gradle 插件工程，必须经 includeBuild 引入；ADR-0026 决策 2）
    includeBuild("build-logic/realserver-acceptance")
    // 构建约定插件（公共构建流程：质量门禁、车道基座、打包断言等；ADR-0027）
    includeBuild("build-logic/build-conventions")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "mpmt"

include(
    "core:domain",
    "core:runtime",
    "core:server",
    "core:client",
    "core:paths",
    "core:config",
    "core:protocol",
    "core:spi",
)

include(
    "modules:smoke",
    "modules:acceptance",
)

// FR-18 上手示例域（非产品玩法，不进发布产物）
include("examples:counter")

include(
    "platform:bukkit",
    "platform:bukkit:bukkit-api",
    "platform:bukkit:common",
    "platform:bukkit:modern",
    "platform:bukkit:1.12.2",
    "platform:bukkit:1.20.1",
    "platform:bukkit:1.21.1",
    "platform:bukkit:26.2",
)

include(
    "platform:fabric:fabric-api",
    "platform:forge:forge-api",
    "platform:neoforge:neoforge-api",
    "platform:sponge:sponge-api",
)

// 平台车道：全部作为根构建子模块（ADR-0026）。
// 根构建硬要求守护 JVM ≥ 25（26.2 两条车道的 loom 与车道脚本在配置期硬校验，ADR-0026 决策 4）。
//
// 命名：loom 的共享服务键为 "LoomJarManifestService:" + project.name（JarManifestService.get）。
// Gradle 的 project.name 恒等于工程路径末段，而跨加载器存在同名版本段
// （fabric 与 forge 都有 1.20.1 / 1.21.1 / 26.2）。同前缀会让两条车道撞键，运行期报
// `JarManifestService$Inject_ cannot be cast to JarManifestService`（jar / remapJar 均受影响）。
// 因此车道路径末段带上加载器前缀（沿用 ADR-0026 背景记录的复合构建名），
// 目录仍为 `platform/<loader>/<版本>`，构建产物路径与验收报告路径均不变。
include(
    "platform:fabric:fabric-1.20.1",
    "platform:fabric:fabric-1.21.1",
    "platform:fabric:fabric-26.2",
    "platform:forge:forge-1.12.2",
    "platform:forge:forge-1.20.1",
    "platform:forge:forge-1.21.1",
    "platform:forge:forge-26.2",
    "platform:neoforge:neoforge-1.20.2",
    "platform:sponge:sponge-1.20.1",
)

// 车道工程目录映射：工程名带加载器前缀，目录仍为 platform/<loader>/<版本>（目录即工程；车道形态见 ADR-0026）。
mapOf(
    "platform:fabric:fabric-1.20.1" to "platform/fabric/1.20.1",
    "platform:fabric:fabric-1.21.1" to "platform/fabric/1.21.1",
    "platform:fabric:fabric-26.2" to "platform/fabric/26.2",
    "platform:forge:forge-1.12.2" to "platform/forge/1.12.2",
    "platform:forge:forge-1.20.1" to "platform/forge/1.20.1",
    "platform:forge:forge-1.21.1" to "platform/forge/1.21.1",
    "platform:forge:forge-26.2" to "platform/forge/26.2",
    "platform:neoforge:neoforge-1.20.2" to "platform/neoforge/1.20.2",
    "platform:sponge:sponge-1.20.1" to "platform/sponge/1.20.1",
).forEach { (path, dir) ->
    project(":$path").projectDir = file(dir)
}
