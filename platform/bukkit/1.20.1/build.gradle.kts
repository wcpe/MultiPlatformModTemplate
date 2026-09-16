import buildconventions.BukkitLaneExtension
import buildconventions.frozenApiSnapshot
import buildconventions.packagingVerification
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// Bukkit 1.20.1 独立产品工程：common + modern + v1_20 → mpmt-bukkit-1.20.1-*.jar
// 验收源集、打包链路、plugin.yml 元数据、编译工具链、单测属性与 realserver 门禁接线由 build-conventions.bukkit 承担。

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

val minecraftVersion = "1.20.1"
val apiCoordinate = "io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT"
val apiSha256 = "161ecf24e6ffb325a79eb7eb04904419c2b80f602672e4758785e9389e9038d1"
val targetJavaVersion = 17
val apiVersion = "1.20"
val productChannel = "mpmt:main"
val acceptanceChannel = "mpmt-test:acceptance"
val regionSchedulerClass = "top.wcpe.mc.mpmt.platform.bukkit.capability.FoliaSchedulerPort"
val adapterClass = "top.wcpe.mc.mpmt.platform.bukkit.version.v1_20.V1_20BukkitVersionAdapter"
val adapterClassPath = "top/wcpe/mc/mpmt/platform/bukkit/version/v1_20/V1_20BukkitVersionAdapter.class"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
}

// 验收控制通道常量由 build-conventions.platform 生成（生成内容与原内联实现逐字节一致）
platformLane {
    mcVersion.set(minecraftVersion)
    channelName.set(acceptanceChannel)
    channelPackage.set("top.wcpe.mc.mpmt.platform.bukkit.acceptance")
    channelClass.set("BukkitAcceptanceControlChannelId")
}

// 车道参数：loader 层接线由 build-conventions.bukkit 承担，车道只声明参数与偏离项
val bukkit = extensions.getByType(BukkitLaneExtension::class.java)
bukkit.mcVersion.set(minecraftVersion)
bukkit.apiCoordinate.set(apiCoordinate)
bukkit.targetJavaVersion.set(targetJavaVersion)
bukkit.apiVersion.set(apiVersion)
bukkit.foliaSupported.set(true)
bukkit.productChannel.set(productChannel)
bukkit.acceptanceChannel.set(acceptanceChannel)
bukkit.regionSchedulerClass.set(regionSchedulerClass)
// 本车道原样：唯一有验收默认轨契约测试源集与托管 Paper 宿主自检任务的车道
bukkit.acceptanceTestSourceSet.set(true)
bukkit.managedPaperHost.set(true)

dependencies {
    // 车道级偏离项：验收契约测试所需的 MockBukkit（仅本车道）
    testImplementation("com.github.seeseemelk:MockBukkit-v1.20:3.88.1")
}

// 冻结 paper-api：插件负责解析配置与 SHA-256 校验，并把校验挂到编译任务之前
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
        must(product.entries.count { it.startsWith(l4Prefix) && it.endsWith("BukkitVersionAdapter.class") } == 1, "产品包含零个或多个 L4 适配器")
        mustServiceEquals(product, adapterService, adapterClass, "产品 adapter services 与目标不符")
        mustContain(product, "top/wcpe/mc/mpmt/core/domain/Mpmt.class", "产品未 shade 核心")
        mustContain(product, "top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class", "产品未 shade SPI")
        mustContain(product, "top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class", "产品缺少入口")
        mustContain(product, "top/wcpe/mc/mpmt/platform/bukkit/capability/FoliaSchedulerPort.class", "现代产品缺少 Folia 调度类")
        mustNotBundle(product, listOf("top/wcpe/mc/mpmt/platform/bukkit/acceptance/"), "产品混入 acceptance")
        mustContain(acceptanceFile, "top/wcpe/mc/mpmt/platform/bukkit/acceptance/MpmtBukkitAcceptancePlugin.class", "验收缺少入口")
        mustNotContain(acceptanceFile, "top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class", "验收混入产品入口")
        mustNotBundle(product, listOf("org/bukkit/", "io/papermc/"), "产品误打入 Bukkit/Paper API")
        mustContainPrefix(product, "top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/", "产品 snakeyaml 未 relocate")
        mustMetadataContains(product, "plugin.yml", "main: top.wcpe.mc.mpmt.platform.bukkit.MpmtBukkitPlugin", "产品 metadata 入口错误")
        mustMetadataContains(product, "plugin.yml", "folia-supported: true", "现代产品缺少 folia 字段")
        mustMetadataContains(product, "plugin.yml", "api-version: '$apiVersion'", "产品 api-version 错误")
        mustMetadataContains(acceptanceFile, "plugin.yml", "MpmtBukkitAcceptancePlugin", "验收 metadata 入口错误")
        mustContain(product, "META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap", "缺少 PlatformBootstrap services")
        log("Bukkit $minecraftVersion 打包校验通过：产品=${product.file.name}，验收=${acceptanceFile.file.name}")
    }
}

val bukkitReportFile = layout.buildDirectory.file("acceptance/server-report.txt")
// SCHEDULER 矩阵报告默认旁路文件；-Pmpmt.acceptance.matrix=SCHEDULER 时门禁读此路径
val schedulerReportFile = layout.buildDirectory.file("acceptance/server-report-scheduler.txt")
val acceptanceMatrix =
    providers.gradleProperty("mpmt.acceptance.matrix").orElse("")

mpmtRealServerAcceptance {
    // 本车道偏离项：Folia（SCHEDULER）与 Paper 默认报告分离，避免默认轨全量报告冒充矩阵报告
    reportFile.set(
        acceptanceMatrix.map { matrixId ->
            if (matrixId.equals("SCHEDULER", ignoreCase = true)) {
                schedulerReportFile.get()
            } else {
                bukkitReportFile.get()
            }
        },
    )
}

tasks.named("runRealServerAcceptance") {
    group = "verification"
    description =
        "Bukkit $minecraftVersion realserver 门禁" +
        "（-Pmpmt.realserver.autoHost=true 时接线 PaperHostService；" +
        "-Pmpmt.acceptance.matrix=SCHEDULER 时读 server-report-scheduler.txt）"
    dependsOn(tasks.named("shadowJar"), tasks.named("acceptanceJar"), "verifyMpmtAcceptanceReport")
}
