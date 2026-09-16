import buildconventions.BukkitLaneExtension
import buildconventions.frozenApiSnapshot
import buildconventions.packagingVerification
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// Bukkit 1.12.2 独立产品工程：common + v1_12 适配器 → mpmt-bukkit-1.12.2-*.jar
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

val minecraftVersion = "1.12.2"
val apiCoordinate = "org.spigotmc:spigot-api:1.12.2-R0.1-SNAPSHOT"
val apiSha256 = "22ca0ff290aa2d3066348d623e9c8998e58a49f2fee91bc06e3de96b2544e909"
val targetJavaVersion = 8
val apiVersion = ""
val foliaMetadata = false
val productChannel = "MPMT"
val acceptanceChannel = "MPMTTEST"
val regionSchedulerClass = "top.wcpe.mc.mpmt.platform.bukkit.capability.BukkitSchedulerPort"
val adapterClass = "top.wcpe.mc.mpmt.platform.bukkit.version.v1_12.V1_12BukkitVersionAdapter"
val adapterClassPath = "top/wcpe/mc/mpmt/platform/bukkit/version/v1_12/V1_12BukkitVersionAdapter.class"
val snakeyamlVersion = "2.2"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        name = "Spigot"
    }
    maven("https://oss.sonatype.org/content/repositories/snapshots/") {
        name = "SonatypeSnapshots"
    }
    maven("https://repo.md-5.net/content/repositories/snapshots/") {
        name = "md5Snapshots"
    }
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
bukkit.foliaSupported.set(foliaMetadata)
bukkit.productChannel.set(productChannel)
bukkit.acceptanceChannel.set(acceptanceChannel)
bukkit.regionSchedulerClass.set(regionSchedulerClass)
// 本车道原样：无 modern 模块、无 acceptanceTest 源集、Java 8 工具链无 --release、
// spigot-api 需排除 bungeecord-chat 并由本地补丁 jar 顶替、mpmt.test.javaVersion 用字面量 1.8
bukkit.modernProduct.set(false)
bukkit.releaseTargetVersion.set(false)
bukkit.bungeeChatFallbackJar.set("platform/bukkit/third-party/bungeecord-chat-1.12-SNAPSHOT.jar")
bukkit.testJavaVersion.set("1.8")

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
        mustNotBundle(product, listOf("top/wcpe/mc/mpmt/platform/bukkit/acceptance/"), "产品混入 acceptance")
        mustContain(acceptanceFile, "top/wcpe/mc/mpmt/platform/bukkit/acceptance/MpmtBukkitAcceptancePlugin.class", "验收缺少入口")
        mustNotContain(acceptanceFile, "top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class", "验收混入产品入口")
        mustNotBundle(product, listOf("org/bukkit/", "io/papermc/"), "产品误打入 Bukkit/Paper API")
        mustContainPrefix(product, "top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/", "产品 snakeyaml 未 relocate")
        mustMetadataContains(product, "plugin.yml", "main: top.wcpe.mc.mpmt.platform.bukkit.MpmtBukkitPlugin", "产品 metadata 入口错误")
        must(!product.text("plugin.yml").contains("MpmtBukkitAcceptancePlugin"), "产品 metadata 混入验收入口")
        mustMetadataContains(acceptanceFile, "plugin.yml", "MpmtBukkitAcceptancePlugin", "验收 metadata 入口错误")
        must(product.entries.none { it.endsWith("FoliaSchedulerPort.class") }, "1.12 产品混入 Folia 类")
        must(!product.text("plugin.yml").contains("folia-supported:"), "1.12 产品不得含 folia 字段")
        must(!product.text("plugin.yml").contains("api-version:"), "1.12 产品不得含 api-version")
        mustContain(product, "META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap", "缺少 PlatformBootstrap services")
        log("Bukkit $minecraftVersion 打包校验通过：产品=${product.file.name}，验收=${acceptanceFile.file.name}")
    }
}

tasks.named("runRealServerAcceptance") {
    group = "verification"
    description = "Bukkit $minecraftVersion realserver 门禁"
    dependsOn(tasks.named("shadowJar"), tasks.named("acceptanceJar"), "verifyMpmtAcceptanceReport")
}
