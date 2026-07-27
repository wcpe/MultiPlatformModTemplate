// Fabric 26.2 独立构建（Loom）
pluginManagement {
    repositories {
        maven("https://maven.wcpe.top/repository/maven-releases/") { name = "WCPE Releases" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "platform-fabric-26.2"

includeBuild("../../..")
