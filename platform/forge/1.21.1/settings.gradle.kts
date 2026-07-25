// platform-forge-1.21.1：独立构建（独立 launcher / JDK21）
// 源码按 common / server / client 分目录；本工程保留自有 Gradle 配置（原 modern-1_21）

pluginManagement {
    repositories {
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "platform-forge-1.21.1"


// 反向消费仓库根：坐标 → 收纳后工程路径（禁止拍平路径）
includeBuild("../../..") {
    dependencySubstitution {
        substitute(module("top.wcpe.mc.mpmt:domain")).using(project(":core:domain"))
        substitute(module("top.wcpe.mc.mpmt:runtime")).using(project(":core:runtime"))
        substitute(module("top.wcpe.mc.mpmt:server")).using(project(":core:server"))
        substitute(module("top.wcpe.mc.mpmt:client")).using(project(":core:client"))
        substitute(module("top.wcpe.mc.mpmt:paths")).using(project(":core:paths"))
        substitute(module("top.wcpe.mc.mpmt:config")).using(project(":core:config"))
        substitute(module("top.wcpe.mc.mpmt:protocol")).using(project(":core:protocol"))
        substitute(module("top.wcpe.mc.mpmt:spi")).using(project(":core:spi"))
        substitute(module("top.wcpe.mc.mpmt:acceptance")).using(project(":modules:acceptance"))
        substitute(module("top.wcpe.mc.mpmt:fabric-api")).using(project(":platform:fabric:fabric-api"))
        substitute(module("top.wcpe.mc.mpmt:forge-api")).using(project(":platform:forge:forge-api"))
        substitute(module("top.wcpe.mc.mpmt:neoforge-api")).using(project(":platform:neoforge:neoforge-api"))
        substitute(module("top.wcpe.mc.mpmt:sponge-api")).using(project(":platform:sponge:sponge-api"))
    }
}
