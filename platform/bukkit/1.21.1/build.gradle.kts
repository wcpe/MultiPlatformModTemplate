import buildconventions.BukkitLaneExtension
import buildconventions.frozenApiSnapshot
import buildconventions.packagingVerification
import buildconventions.verifyBukkitProducts
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
    // 断言用到的车道常量在配置期取成局部值：断言 lambda 只捕获这些值，
    // 不捕获脚本对象（配置缓存要求）；断言内容、顺序与失败文案不变。
    val laneMcVersion = minecraftVersion
    val laneAdapterClassPath = adapterClassPath
    val laneAdapterClass = adapterClass
    val laneApiVersion = apiVersion
    packagingVerification(
        laneLabel = "Bukkit",
        mcVersion = laneMcVersion,
        product = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile },
        acceptance = tasks.named<ShadowJar>("acceptanceJar").flatMap { it.archiveFile },
    ) { product, acceptance ->
        verifyBukkitProducts(
            product = product,
            acceptance = acceptance,
            mcVersion = laneMcVersion,
            adapterClassPath = laneAdapterClassPath,
            adapterClass = laneAdapterClass,
            l4Prefix = "top/wcpe/mc/mpmt/platform/bukkit/version/v1_",
            expectFoliaSchedulerClass = true,
            rejectProductMainInAcceptance = false,
            rejectAcceptanceInProductYml = false,
            rejectModernYmlFields = false,
            apiVersion = laneApiVersion,
        )
    }
}
