// 根复合构建（ADR-0007）
// 物理布局即工程路径：core/* · platform/<loader>/* · modules/*
// 直接 include。模块坐标 = group + project.name（path 末段）。

pluginManagement {
    repositories {
        // 上游 marker 临时不可用时，仅从本机 Maven 仓库解析 mc-testkit marker 与实现模块。
        mavenLocal {
            content {
                includeGroup("top.wcpe.mc-testkit")
                includeGroup("top.wcpe.mc")
            }
        }
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
    if (!skip("mpmt.skip.fabric") && !skip("mpmt.skip.fabric.26.2")) {
        includeBuild("platform/fabric/26.2") {
            name = "platform-fabric-26.2"
        }
    }
    val rootCannotRunForge120 =
        org.gradle.util.GradleVersion.current() >= org.gradle.util.GradleVersion.version("9.0")
    if (!skip("mpmt.skip.forge") && !skip("mpmt.skip.forge.1.20.1") && !rootCannotRunForge120) {
        includeBuild("platform/forge/1.20.1") {
            name = "platform-forge-1.20.1"
        }
    } else if (rootCannotRunForge120) {
        logger.lifecycle("[mpmt] 根 Gradle 9 不加载 Forge 1.20.1（ForgeGradle 6 仅支持 Gradle 8；请用其独立车道）")
    }
    if (!skip("mpmt.skip.neoforge")) {
        logger.lifecycle("[mpmt] 根 Gradle 不加载 NeoForge 1.20.2（请使用其 Gradle 8.14.5 自有 wrapper）")
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
