import buildconventions.BukkitLaneExtension
import buildconventions.frozenApiSnapshot
import buildconventions.packagingVerification
import buildconventions.verifyBukkitProducts
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.spotbugs.snom.SpotBugsExtension

// Bukkit 26.2 车道（根构建子模块）：common + modern + v26_2 → mpmt-bukkit-26.2-<version>.jar。
// 不可变契约：产物名与路径、验收控制通道常量、冻结 paper-api 坐标与 SHA-256、冻结 Paper 运行时
// （build / 大小 / SHA-256）、plugin.yml 断言、真服门禁的报告路径与判定强度（ADR-0014）。
// 流程集中在 build-conventions.{quality,platform,bukkit}（ADR-0027），本脚本只留参数与偏离项。

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
val laneArgCompilerJavaVersion = compilerJavaVersion
val laneArgMinecraftVersion = minecraftVersion
val laneArgProductChannel = productChannel
val laneArgRegionSchedulerClass = regionSchedulerClass
val laneArgTargetJavaVersion = targetJavaVersion
bukkitLane {
    mcVersion.set(laneArgMinecraftVersion)
    apiCoordinate.set(laneArgApiCoordinate)
    targetJavaVersion.set(laneArgTargetJavaVersion)
    compilerJavaVersion.set(laneArgCompilerJavaVersion)
    apiVersion.set(laneArgApiVersion)
    foliaSupported.set(true)
    productChannel.set(laneArgProductChannel)
    acceptanceChannel.set(laneArgAcceptanceChannel)
    regionSchedulerClass.set(laneArgRegionSchedulerClass)
}

// 插件公开 API 需要扩展实例（块外传参用），故在此取回一次
val bukkit = extensions.getByType(buildconventions.BukkitLaneExtension::class.java)

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
    compileOnly("com.google.guava:guava:33.7.1-jre")
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
    add(acceptance.compileOnlyConfigurationName, "com.google.guava:guava:33.7.1-jre")
    add(acceptance.compileOnlyConfigurationName, "org.jetbrains:annotations:26.0.2")
}

// 冻结 API 快照（本车道为 paper-api）：插件解析坐标与 SHA-256，并把校验挂到编译任务之前
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
            l4Prefix = null,
            expectFoliaSchedulerClass = true,
            rejectProductMainInAcceptance = false,
            rejectAcceptanceInProductYml = false,
            rejectModernYmlFields = false,
            apiVersion = laneApiVersion,
        )
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
