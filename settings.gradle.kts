// 根复合构建（ADR-0007）：共享核心为根构建常规模块；带专属插件的平台各为独立 includeBuild。
// 本期（M0）仅落地 core-domain 与 platform-fabric 一例，验证骨架与打包链路；其余平台后续各轮再加。

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // 工具链自动解析：本机已装的 JDK 8 / 17 优先复用，缺失时按需下载（让模板克隆即可构建）
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "mpmt"

// 共享核心（L0–L2），常规 java-library 模块（ADR-0007 决策点 1）
include("core-domain")
include("protocol")

// 带专属 Gradle 插件的平台（Fabric/Loom）为独立 includeBuild，隔离其工具链（ADR-0007 决策点 2）。
// platform-fabric 经其自身 includeBuild("..") 反向消费 core 共享坐标（依赖替换，见 ADR-0012）。
includeBuild("platform-fabric")
