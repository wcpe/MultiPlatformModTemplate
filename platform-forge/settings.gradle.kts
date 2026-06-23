// platform-forge：独立 Gradle 构建，仅应用 ForgeGradle（ADR-0007，隔离加载器专属插件）。
// 经 includeBuild("..") 反向消费根构建的共享核心坐标（依赖替换，ADR-0012）。

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "platform-forge"

// 反向引入根构建以消费 core / platform-spi 共享坐标
includeBuild("..")
