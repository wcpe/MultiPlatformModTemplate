// Fabric 1.20.1 独立构建（Loom）
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

rootProject.name = "platform-fabric-1.20.1"

// 复合构建自动把根工程按 group:name 替换；坐标末段须与工程 path 末段一致
includeBuild("../../..")
