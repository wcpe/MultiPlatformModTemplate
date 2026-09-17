import buildconventions.BukkitLaneExtension
import buildconventions.frozenApiSnapshot
import buildconventions.packagingVerification
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// Bukkit 1.21.1 车道（根构建子模块）：common + modern + v1_21 → mpmt-bukkit-1.21.1-<version>.jar。
// 不可变契约：产物名与路径、验收控制通道常量、冻结 paper-api 坐标与 SHA-256、plugin.yml 断言、
// 真服门禁的报告路径与判定强度（ADR-0014）。验收源集 / 打包链路 / 编译工具链 / 单测属性 / 门禁接线
// 由 build-conventions.{quality,platform,bukkit} 承担（ADR-0027），本脚本只留参数与偏离项。

plugins {
    id("build-conventions.quality")
    id("build-conventions.platform")
    java
    id("com.gradleup.shadow") version "8.3.11"
    // 车道约定插件须晚于 shadow（打包链路按名取 jar/shadowJar），早于 realserver 门禁（验收扩展取值先落地）
    id("build-conventions.bukkit")
    id("top.wcpe.mc.mpmt.realserver-acceptance")
}

group = "top.wcpe.mc.mpmt"
version = rootProject.file("VERSION").readText().trim()

val minecraftVersion = "1.21.1"
val apiCoordinate = "io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT"
val apiSha256 = "b8df3e7f2739e21072a5263e41b307bd30cfa8d8f72258ce27973167f8ad07c0"
val targetJavaVersion = 21
val apiVersion = "1.21"
val productChannel = "mpmt:main"
val acceptanceChannel = "mpmt-test:acceptance"
val regionSchedulerClass = "top.wcpe.mc.mpmt.platform.bukkit.capability.FoliaSchedulerPort"
val adapterClass = "top.wcpe.mc.mpmt.platform.bukkit.version.v1_21.V1_21BukkitVersionAdapter"
val adapterClassPath = "top/wcpe/mc/mpmt/platform/bukkit/version/v1_21/V1_21BukkitVersionAdapter.class"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
}

// 验收控制通道常量的源码由 build-conventions.platform 生成；车道只给版本、通道名与目标类名
platformLane {
    mcVersion.set(minecraftVersion)
    channelName.set(acceptanceChannel)
    channelPackage.set("top.wcpe.mc.mpmt.platform.bukkit.acceptance")
    channelClass.set("BukkitAcceptanceControlChannelId")
}

// 车道参数：loader 层接线由 build-conventions.bukkit 承担，车道只声明参数与偏离项
val laneArgAcceptanceChannel = acceptanceChannel
val laneArgApiCoordinate = apiCoordinate
val laneArgApiVersion = apiVersion
val laneArgMinecraftVersion = minecraftVersion
val laneArgProductChannel = productChannel
val laneArgRegionSchedulerClass = regionSchedulerClass
val laneArgTargetJavaVersion = targetJavaVersion
bukkitLane {
    mcVersion.set(laneArgMinecraftVersion)
    apiCoordinate.set(laneArgApiCoordinate)
    targetJavaVersion.set(laneArgTargetJavaVersion)
    apiVersion.set(laneArgApiVersion)
    foliaSupported.set(true)
    productChannel.set(laneArgProductChannel)
    acceptanceChannel.set(laneArgAcceptanceChannel)
    regionSchedulerClass.set(laneArgRegionSchedulerClass)
}

// 插件公开 API 需要扩展实例（块外传参用），故在此取回一次
val bukkit = extensions.getByType(buildconventions.BukkitLaneExtension::class.java)

// 冻结 API 快照（本车道为 paper-api）：插件解析坐标与 SHA-256，并把校验挂到编译任务之前
frozenApiSnapshot(
    laneLabel = "Bukkit",
    coordinate = apiCoordinate,
    expectedSha256 = apiSha256,
    mcVersion = minecraftVersion,
)

val verifyPackaging by tasks.registering {
    group = "verification"
    description = "校验 Bukkit $minecraftVersion 产品/验收产物"
    dependsOn(tasks.named("shadowJar"), tasks.named("acceptanceJar"))
    packagingVerification(
        laneLabel = "Bukkit",
        mcVersion = minecraftVersion,
        product = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile },
        acceptance = tasks.named<ShadowJar>("acceptanceJar").flatMap { it.archiveFile },
    ) { product, acceptance ->
        val acceptanceFile = acceptance ?: error("缺少验收产物输入")
        val adapterService =
            "META-INF/services/top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter"
        val l4Prefix = "top/wcpe/mc/mpmt/platform/bukkit/version/v1_"

        must(product.file.name.contains(minecraftVersion), "产品产物名未包含 MC 版本")
        must(acceptanceFile.file.name.contains(minecraftVersion), "验收产物名未包含 MC 版本")
        mustContain(product, adapterClassPath, "产品缺少选中的 L4 适配器")
        must(
            product.entries.count { it.startsWith(l4Prefix) && it.endsWith("BukkitVersionAdapter.class") } == 1,
            "产品包含零个或多个 L4 适配器",
        )
        mustServiceEquals(product, adapterService, adapterClass, "产品 adapter services 与目标不符")
        mustContain(product, "top/wcpe/mc/mpmt/core/domain/Mpmt.class", "产品未 shade 核心")
        mustContain(product, "top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class", "产品未 shade SPI")
        mustContain(product, "top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class", "产品缺少入口")
        mustContain(
            product,
            "top/wcpe/mc/mpmt/platform/bukkit/capability/FoliaSchedulerPort.class",
            "现代产品缺少 Folia 调度类",
        )
        mustNotBundle(product, listOf("top/wcpe/mc/mpmt/platform/bukkit/acceptance/"), "产品混入 acceptance")
        mustContain(
            acceptanceFile,
            "top/wcpe/mc/mpmt/platform/bukkit/acceptance/MpmtBukkitAcceptancePlugin.class",
            "验收缺少入口",
        )
        mustNotBundle(product, listOf("org/bukkit/", "io/papermc/"), "产品误打入 Bukkit/Paper API")
        mustContainPrefix(product, "top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/", "产品 snakeyaml 未 relocate")
        mustMetadataContains(
            product,
            "plugin.yml",
            "main: top.wcpe.mc.mpmt.platform.bukkit.MpmtBukkitPlugin",
            "产品 metadata 入口错误",
        )
        mustMetadataContains(product, "plugin.yml", "folia-supported: true", "现代产品缺少 folia 字段")
        mustMetadataContains(product, "plugin.yml", "api-version: '$apiVersion'", "产品 api-version 错误")
        mustMetadataContains(acceptanceFile, "plugin.yml", "MpmtBukkitAcceptancePlugin", "验收 metadata 入口错误")
        mustContain(
            product,
            "META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap",
            "缺少 PlatformBootstrap services",
        )
        log("Bukkit $minecraftVersion 打包校验通过：产品=${product.file.name}，验收=${acceptanceFile.file.name}")
    }
}

tasks.named("runRealServerAcceptance") {
    group = "verification"
    description = "Bukkit $minecraftVersion realserver 门禁"
    dependsOn(tasks.named("shadowJar"), tasks.named("acceptanceJar"), "verifyMpmtAcceptanceReport")
}
