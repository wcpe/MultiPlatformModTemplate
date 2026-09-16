import buildconventions.BukkitLaneExtension
import buildconventions.frozenApiSnapshot
import buildconventions.packagingVerification
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.spotbugs.snom.SpotBugsExtension

// Bukkit 26.2 独立产品工程：common + modern + v26_2 → mpmt-bukkit-26.2-*.jar
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

val minecraftVersion = "26.2"
val paperRuntimeBuild = 71
val paperRuntimeSizeBytes = 61_744_713L
val paperRuntimeSha256 = "36fee4f3a7020eb2e2d6f8d70d849beaf0f024d86f09302b9ccf2d96f266127e"
val apiCoordinate = "io.papermc.paper:paper-api:26.2.build.72-beta"
val apiSha256 = "ff4dd8b88beb95e990a900f587da3644d44345ce2bc6e8a11b851f6dfb98742b"
val compilerJavaVersion = 25 // 读 paper-api major 69
val targetJavaVersion = 25 // paper-api 元数据要求 JVM 25，产物目标随之为 25
val apiVersion = "26.2"
val productChannel = "mpmt:main"
val acceptanceChannel = "mpmt-test:acceptance"
val regionSchedulerClass = "top.wcpe.mc.mpmt.platform.bukkit.capability.FoliaSchedulerPort"
val adapterClass = "top.wcpe.mc.mpmt.platform.bukkit.version.v26_2.V26_2BukkitVersionAdapter"
val adapterClassPath = "top/wcpe/mc/mpmt/platform/bukkit/version/v26_2/V26_2BukkitVersionAdapter.class"

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
bukkit.compilerJavaVersion.set(compilerJavaVersion)
bukkit.apiVersion.set(apiVersion)
bukkit.foliaSupported.set(true)
bukkit.productChannel.set(productChannel)
bukkit.acceptanceChannel.set(acceptanceChannel)
bukkit.regionSchedulerClass.set(regionSchedulerClass)

// 验收源集由 build-conventions.bukkit 创建；本车道偏离项：显式钉住编译期 adventure / guava / gson 版本
// （Paper 元数据亦会传递，这里避免解析漂移；均不入产物），故直接往验收源集配置上加依赖。
val acceptance = sourceSets.getByName("acceptance")

dependencies {
    compileOnly(platform("net.kyori:adventure-bom:5.2.0"))
    compileOnly("net.kyori:adventure-api")
    compileOnly("net.kyori:adventure-key")
    compileOnly("net.kyori:adventure-text-minimessage")
    compileOnly("net.kyori:adventure-text-serializer-gson")
    compileOnly("net.kyori:adventure-text-serializer-legacy")
    compileOnly("net.kyori:adventure-text-serializer-plain")
    compileOnly("net.kyori:adventure-text-logger-slf4j")
    compileOnly("com.google.guava:guava:33.6.0-jre")
    compileOnly("com.google.code.gson:gson:2.14.0")
    compileOnly("org.jetbrains:annotations:26.0.2")
    testImplementation(platform("net.kyori:adventure-bom:5.2.0"))
    testImplementation("net.kyori:adventure-api")

    add(acceptance.compileOnlyConfigurationName, platform("net.kyori:adventure-bom:5.2.0"))
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-api")
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-key")
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-text-minimessage")
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-text-serializer-gson")
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-text-serializer-legacy")
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-text-serializer-plain")
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-text-logger-slf4j")
    add(acceptance.compileOnlyConfigurationName, "com.google.guava:guava:33.6.0-jre")
    add(acceptance.compileOnlyConfigurationName, "org.jetbrains:annotations:26.0.2")
}

// 冻结 paper-api：插件负责解析配置与 SHA-256 校验，并把校验挂到编译任务之前
frozenApiSnapshot(
    laneLabel = "Bukkit",
    coordinate = apiCoordinate,
    expectedSha256 = apiSha256,
    mcVersion = minecraftVersion,
)

configure<SpotBugsExtension> {
    toolVersion.set("4.9.8")
}

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
        must(product.file.name.contains(minecraftVersion), "产品产物名未包含 MC 版本")
        must(acceptanceFile.file.name.contains(minecraftVersion), "验收产物名未包含 MC 版本")
        mustContain(product, adapterClassPath, "产品缺少选中的 L4 适配器")
        must(
            product.entries.count {
                it.endsWith("BukkitVersionAdapter.class") && !it.endsWith("ModernBukkitVersionAdapter.class") && it != "top/wcpe/mc/mpmt/platform/bukkit/version/BukkitVersionAdapter.class"
            } == 1,
            "产品包含零个或多个 L4 适配器",
        )
        mustServiceEquals(product, adapterService, adapterClass, "产品 adapter services 与目标不符")
        mustContain(product, "top/wcpe/mc/mpmt/core/domain/Mpmt.class", "产品未 shade 核心")
        mustContain(product, "top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class", "产品未 shade SPI")
        mustContain(product, "top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class", "产品缺少入口")
        mustContain(product, "top/wcpe/mc/mpmt/platform/bukkit/capability/FoliaSchedulerPort.class", "现代产品缺少 Folia 调度类")
        mustNotBundle(product, listOf("top/wcpe/mc/mpmt/platform/bukkit/acceptance/"), "产品混入 acceptance")
        mustContain(acceptanceFile, "top/wcpe/mc/mpmt/platform/bukkit/acceptance/MpmtBukkitAcceptancePlugin.class", "验收缺少入口")
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

// 26.2 车道没有"默认轨"：其唯一有效矩阵即 REALSERVER262（见 PlatformLane.BUKKIT_262.defaultMatrix）。
// 因此在本轮上下文（带 -Pmpmt.acceptance.runId）下若未显式声明矩阵，就按 REALSERVER262 解析报告——
// 使跨 lane 聚合门（只有一个全局矩阵值，无法逐 lane 区分）也能正确定位本车道的报告；
// 不带 runId 时保持原有"默认轨"行为，不改变独立调用语义。
val acceptanceMatrix =
    providers
        .gradleProperty("mpmt.acceptance.matrix")
        .orElse(
            providers.gradleProperty("mpmt.acceptance.runId").flatMap { runId ->
                if (runId.isBlank()) providers.provider { "" } else providers.provider { "REALSERVER262" }
            },
        )
val bukkitReportFile =
    acceptanceMatrix.flatMap { matrix ->
        val reportName = if (matrix.isBlank()) "server-report.txt" else "server-report-${matrix.lowercase()}.txt"
        layout.buildDirectory.file("acceptance/$reportName")
    }
val fabric262Product =
    rootProject.layout.projectDirectory.file(
        "platform/fabric/26.2/build/libs/mpmt-fabric-26.2-${rootProject.version}.jar",
    )

mpmtRealServerAcceptance {
    // 本车道偏离项：矩阵命名的报告路径、冻结 Paper 运行时与跨车道客户端制品；其余接线由约定插件承担
    reportFile.set(bukkitReportFile)
    matrix.set(acceptanceMatrix)
    paperBuild.set(paperRuntimeBuild)
    paperJarSizeBytes.set(paperRuntimeSizeBytes)
    paperJarSha256.set(paperRuntimeSha256)
    paperJavaVersion.set(compilerJavaVersion)
    acceptanceClientProductJar.set(fabric262Product)
    acceptanceClientAcceptanceJar.set(fabric262Product)
    acceptanceRunId.set(providers.gradleProperty("mpmt.acceptance.runId").orElse(""))
    acceptanceStartEpochMs.set(providers.gradleProperty("mpmt.acceptance.startEpochMs").orElse(""))
}

tasks.named("runRealServerAcceptance") {
    group = "verification"
    description = "Bukkit $minecraftVersion realserver 门禁"
    dependsOn(tasks.named("shadowJar"), tasks.named("acceptanceJar"), "verifyMpmtAcceptanceReport")
}
