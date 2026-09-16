import buildconventions.NeoForgeLaneExtension

// NeoForge 1.20.2 车道（根构建子模块）：common + server + client 分目录 → mpmt-neoforge-1.20.2-<version>.jar。
// 不可变契约：产物名与路径、打包链路（shade 共享核心 + relocate snakeyaml，ADR-0012）、remapJar 恒等重映射
// （NeoForge 运行期用官方 Mojmap、无 SRG）、mods.toml 的 Mixin 声明（内置、无 refmap）、真服报告路径与判定强度
// （ADR-0014）；锚点 MC 1.20.2（NeoForge 无 1.20.1，PRD §7）。
// loader 层（依赖接线、dev run、mods.toml 展开、打包、验收接入与门禁）由 build-conventions.neoforge 承担（ADR-0027）。
plugins {
    id("build-conventions.quality")
    `java-library`
    id("top.wcpe.loom")
    // 8.3.11：修复 RelocatorRemapper.mapValue 与 loom 依赖树上新 ASM（visitLdcInsn 传 Type）的不兼容
    id("com.gradleup.shadow") version "8.3.11"
    // 车道约定插件须晚于 java-library / top.wcpe.loom / shadow：验收源集、打包链路与 dev run 都挂在它们之上
    id("build-conventions.neoforge")
    // 分析类插件（spotbugs / ktlint / detekt / kover）不在车道内声明：由 build-conventions.quality 应用，
    // 版本与类路径由根 plugins{} 单点 pin，重复声明会分裂插件类加载器并破坏 loom 的清单服务。
}

group = "top.wcpe.mc.mpmt"

base {
    // 单锚点 1.20.2；产物名带版本以免与多版本矩阵混淆
    archivesName.set("mpmt-neoforge-1.20.2")
}

// 单版本构建内：common / server / client 分目录（服客分离）
sourceSets.named("main") {
    java.setSrcDirs(
        listOf(
            "common/src/main/java",
            "server/src/main/java",
            "client/src/main/java",
        ),
    )
    resources.setSrcDirs(listOf("common/src/main/resources"))
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
}
// 质量工具链：装配由 build-conventions.quality 插件承担（本工程无偏离项）

// 专用配置：需 shade 的内容（产品：core/spi + 第三方；验收：acceptance 核心），由插件按名消费
val shadowBundle: Configuration by configurations.creating
val acceptanceShadowBundle: Configuration by configurations.creating

// neoforge 车道参数：版本、目标 JDK、验收驱动取值在此声明；其余 loader 层接线由插件承担
val neoforgeLane = extensions.getByType(NeoForgeLaneExtension::class.java)
neoforgeLane.mcVersion.set("1.20.2")
neoforgeLane.neoForgeVersion.set("20.2.93")
neoforgeLane.snakeyamlVersion.set("2.2")
neoforgeLane.targetJavaVersion.set(17)
neoforgeLane.acceptanceJarName.set("mpmt-acceptance-neoforge")
neoforgeLane.acceptanceMainClass.set("top.wcpe.mc.mpmt.platform.neoforge.acceptance.sim.NeoForgeDefaultSimulation")
neoforgeLane.acceptanceReport.set("run-server/acceptance-report.txt")

// userdev 单坐标拆分（arch-loom 三段式）：原版 MC 本体 + 官方 Mojang 映射（ADR-0016）+ NeoForge
// （arch-loom 的 neoForge 配置，由其 installer-tools 管线解析 userdev 并产出 patched MC dev jar）。
// 三段式坐标必须在车道脚本内声明：loom 在自身的 afterEvaluate 立即校验三者非空，晚于该时点的声明会让
// userdev 管线直接失败。产品 shade 闭包、单测与验收依赖接线仍由插件承担。
dependencies {
    minecraft("com.mojang:minecraft:${neoforgeLane.mcVersion.get()}")
    mappings(loom.officialMojangMappings())
    "neoForge"("net.neoforged:neoforge:${neoforgeLane.neoForgeVersion.get()}")
}
