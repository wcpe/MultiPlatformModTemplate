import buildconventions.NeoForgeLaneExtension
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// platform-neoforge（L3）：根构建子模块，应用 arch-loom（top.wcpe.loom，ADR-0007，隔离加载器专属插件）。
// 锚点 MC 1.20.2（NeoForge 无 1.20.1；PRD §7）。NeoForge 运行期用官方 Mojmap（arch-loom usesMojangAtRuntime
// 对 neoforge 平台恒真 → remapJar 恒等重映射、无 SRG，区别于 Forge）；Mixin 内置（mods.toml [[mixins]] 声明、
// 无 refmap）。打包链路（ADR-0012）：shade 共享核心 + relocate snakeyaml → remapJar 产出最终产品 jar。
// dev run classpath 墙同 FG：受控 JAR 不会自动进入 dev mod 运行期类路径，经 coreLibJar（FMLModType:GAMELIBRARY）
// 放 run-*/mods 暴露。
// loader 层（依赖接线、dev run、mods.toml 展开、打包链路、验收接入与真服门禁）由 build-conventions.neoforge
// 承担，本脚本只留参数与差异。

plugins {
    id("build-conventions.quality")
    `java-library`
    id("top.wcpe.loom")
    // 8.3.11：修复 RelocatorRemapper.mapValue 与 loom 依赖树上新 ASM（visitLdcInsn 传 Type）的不兼容
    id("com.gradleup.shadow") version "8.3.11"
    // 车道约定插件须晚于 java-library / top.wcpe.loom / shadow：验收源集、打包链路与 dev run 都挂在它们之上
    id("build-conventions.neoforge")
    // 静态分析 / 质量工具链由根构建 subprojects{} 统一提供（含 spotbugs/ktlint/detekt/kover，见 ADR-0026）。
    // 车道内重复声明会分裂插件类加载器并破坏 loom 清单服务，故此处不再声明。
    // 历史说明：静态分析 / 质量工具链（严格门禁，static-analysis.md）：与根构建同一套，共享仓库根 config/ 规则集。
    // 核心 Gradle 插件经 apply(plugin=...) 接入（见下方装配块）；外部插件在此带版本直接 apply。
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

// dev run 用 core 库 jar：shade core/spi（含传递 protocol/core-domain）+ relocate snakeyaml，带
// FMLModType:GAMELIBRARY manifest，放 run-*/mods 让 FML 当 game library 暴露给 mod（绕 dev classpath 墙）。
// 仅 dev run 用、不发布、不入产品 mod jar。
val coreLibJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "dev run 用 core 库 jar（FMLModType:GAMELIBRARY，放 run-*/mods 绕 dev classpath 墙）"
    archiveBaseName.set("mpmt-neoforge-corelib")
    archiveClassifier.set("")
    configurations = listOf(shadowBundle)
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    exclude("META-INF/maven/**")
    manifest { attributes("FMLModType" to "GAMELIBRARY") }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
