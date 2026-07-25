// 根复合构建（ADR-0007）
// 物理布局即工程路径：core/* · platform/<loader>/* · modules/*
// 直接 include。模块坐标 = group + project.name（path 末段）。

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.wcpe.top/repository/maven-public/")
    }
    includeBuild("build-logic/realserver-acceptance")
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

include(
    "platform:bukkit",
    "platform:bukkit:bukkit-api",
    "platform:bukkit:common",
    "platform:bukkit:modern",
    "platform:bukkit:1.12.2",
    "platform:bukkit:1.20.1",
    "platform:bukkit:1.21.1",
)

include(
    "platform:fabric:fabric-api",
    "platform:forge:forge-api",
    "platform:neoforge:neoforge-api",
    "platform:sponge:sponge-api",
)

if (gradle.parent == null) {
    fun skip(flag: String): Boolean =
        settings.startParameter.projectProperties[flag] == "true" ||
            providers.gradleProperty(flag).orNull == "true"

    if (!skip("mpmt.skip.fabric") && !skip("mpmt.skip.fabric.1.20.1")) {
        includeBuild("platform/fabric/1.20.1") {
            name = "platform-fabric-1.20.1"
        }
    }
    if (!skip("mpmt.skip.fabric") && !skip("mpmt.skip.fabric.1.21.1")) {
        includeBuild("platform/fabric/1.21.1") {
            name = "platform-fabric-1.21.1"
        }
    }
    if (!skip("mpmt.skip.forge") && !skip("mpmt.skip.forge.1.20.1")) {
        includeBuild("platform/forge/1.20.1") {
            name = "platform-forge-1.20.1"
        }
    }
    if (!skip("mpmt.skip.neoforge")) {
        includeBuild("platform/neoforge/1.20.2") {
            name = "platform-neoforge"
        }
    }
    if (!skip("mpmt.skip.sponge")) {
        includeBuild("platform/sponge/1.20.1") {
            name = "platform-sponge"
        }
    }
    includeBuild("build-logic/realserver-acceptance") {
        name = "mpmt-realserver-acceptance-logic"
    }
}
