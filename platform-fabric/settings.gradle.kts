// platform-fabric：独立 Gradle 构建，仅应用 Loom（ADR-0007，隔离加载器专属插件）。
// 经 includeBuild("..") 反向消费根构建的共享核心坐标 top.wcpe.mc.mpmt:core-*（依赖替换，ADR-0012）。

pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // 工具链自动解析：复用本机 JDK 17，缺失时按需下载
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "platform-fabric"

// 反向引入根构建以消费 core 共享坐标（M0 打包 spike 的关键验证点，见 ADR-0012）
includeBuild("..")
