package top.wcpe.mc.mpmt.gradle.realserver

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class RealServerGateContractTest {
    @Test
    fun `版本矩阵门不混入 26_2 且 262 三车道有独立入口`() {
        val build = readRootFile("build.gradle.kts")
        val matrixBuild = block(build, "tasks.register(\"verifyVersionMatrixBuild\")")
        val matrixAcceptance = block(build, "tasks.register(\"runVersionMatrixRealServerAcceptance\")")

        assertFalse(matrixBuild.contains("26.2"))
        assertFalse(matrixAcceptance.contains("262"))
        assertTrue(build.contains("tasks.register(\"buildRealServerArtifacts262\")"))
        assertTrue(build.contains("tasks.register(\"runRealServerAcceptance262\")"))
        assertTrue(build.contains("runRealServerAcceptanceBukkit262"))
        assertTrue(build.contains("runRealServerAcceptanceFabric262"))
        assertTrue(build.contains("runRealServerAcceptanceForge262"))
    }

    @Test
    fun `发布聚合收集全部平台产物且全部车道为根子模块`() {
        val build = readRootFile("build.gradle.kts")

        // ADR-0026：13 个发布 jar 均由 project(...) 的 buildDirectory 解析，任务依赖补齐
        assertTrue(build.contains("platform:fabric:fabric-26.2"))
        assertTrue(build.contains("mpmt-fabric-26.2-${'$'}version.jar"))
        assertTrue(build.contains("mpmt-forge-26.2-${'$'}version.jar"))
        assertFalse(build.contains("Forge 26.2 产物（可选）"))
        assertFalse(build.contains("用自有 wrapper 运行"))
        assertTrue(build.contains("发布制品缺失"))
        // 全部车道（含跨代 Forge / NeoForge / Sponge）改为项目任务依赖
        listOf(
            ":platform:forge:forge-1.12.2:reobfJar",
            ":platform:forge:forge-1.21.1:packageArtifacts",
            ":platform:forge:forge-26.2:packageArtifacts",
            ":platform:neoforge:neoforge-1.20.2:packageArtifacts",
            ":platform:sponge:sponge-1.20.1:shadowJar",
        ).forEach { taskPath ->
            assertTrue(build.contains(taskPath), "缺少任务依赖：$taskPath")
        }
        assertFalse(build.contains("dependsOnIncludedIfPresent"))
        assertFalse(build.contains("gradle.includedBuilds"))
    }

    @Test
    fun `Fabric 26_2 为根子模块并经同名构建项目产物消费核心`() {
        val build = readRootFile("platform/fabric/26.2/build.gradle.kts")
        val rootBuild = readRootFile("build.gradle.kts")
        val rootSettings = readRootFile("settings.gradle.kts")

        // ADR-0026：车道不再持有独立 settings / 自有 wrapper / 反向 includeBuild
        assertFalse(Files.exists(repositoryRoot().resolve("platform/fabric/26.2/settings.gradle.kts")))
        assertFalse(Files.exists(repositoryRoot().resolve("platform/fabric/26.2/gradlew")))
        assertFalse(build.contains("includeBuild"))
        // 根 settings 直接 include，且插件版本在根单点 pin
        assertTrue(rootSettings.contains("\"platform:fabric:fabric-26.2\""))
        assertTrue(rootSettings.contains("top.wcpe.loom") && rootSettings.contains("1.17.1"))
        // 受控内部 JAR 改为同根构建项目产物
        assertTrue(build.contains("fun moduleJar("))
        assertTrue(build.contains("moduleJar(\":core:domain\")"))
        assertFalse(build.contains("verifyInternalJars"))
        assertFalse(rootBuild.contains("prepareFabric262Inputs"))
        assertTrue(rootBuild.contains("dependsOn(\":platform:fabric:fabric-26.2:build\")"))
        assertFalse(build.contains("tasks.named<RemapJarTask>(\"remapJar\")"))
        // Loom run 与报告路径由车道约定插件承担：车道只声明"run 报告跟随 -Pmpmt.acceptance.report 覆盖"，
        // 覆盖解析（-P 优先，否则 build/acceptance/server-report.txt）与 run 属性注入在插件单点实现，属性名不变。
        assertTrue(build.contains("id(\"build-conventions.fabric\")"))
        assertTrue(build.contains("fabric.acceptanceServerUsesOverriddenReport.set(true)"))
        assertTrue(build.contains("acceptanceReportFile(matrixId)"))
        val fabricRuns =
            readRootFile("build-logic/build-conventions/src/main/kotlin/buildconventions/FabricRuns.kt")
        assertTrue(fabricRuns.contains("acceptanceServerUsesOverriddenReport"))
        assertTrue(fabricRuns.contains("AcceptanceRound.REPORT_PROPERTY"))
        assertTrue(fabricRuns.contains("\"property\"(\"mpmt.acceptance.report\", report)"))
        assertTrue(build.contains("val systemPropertyPrefixes = task.systemProperties.keys.map"))
        assertTrue(build.contains("command += task.systemProperties.map"))
        assertTrue(build.contains("TimeUnit.SECONDS.toNanos(300)"))
    }

    @Test
    fun `Forge 1_20_1 为根子模块且不再有 skip flag`() {
        val settings = readRootFile("settings.gradle.kts")

        assertTrue(settings.contains("\"platform:forge:forge-1.20.1\""))
        assertFalse(settings.contains("includeBuild(\"platform/forge/1.20.1\")"))
        assertFalse(settings.contains("mpmt.skip"))
        assertFalse(settings.contains("ForgeGradle 6 仅支持 Gradle 8"))
    }

    @Test
    fun `mc testkit 插件有最小范围的本地 Maven 回退`() {
        val settings = readRootFile("settings.gradle.kts")

        assertTrue(settings.contains("mavenLocal {"))
        assertTrue(settings.contains("includeGroup(\"top.wcpe.mc-testkit\")"))
        assertTrue(settings.contains("includeGroup(\"top.wcpe.mc\")"))
    }

    @Test
    fun `NeoForge 1_20_2 为根子模块并统一插件与 Gradle`() {
        val rootBuild = readRootFile("build.gradle.kts")
        val rootSettings = readRootFile("settings.gradle.kts")
        val neoBuild = readRootFile("platform/neoforge/1.20.2/build.gradle.kts")

        assertTrue(rootSettings.contains("\"platform:neoforge:neoforge-1.20.2\""))
        assertFalse(rootSettings.contains("includeBuild(\"platform/neoforge/1.20.2\")"))
        assertFalse(rootSettings.contains("根 Gradle 不加载 NeoForge 1.20.2"))
        // ADR-0025：插件统一 top.wcpe.loom，由根 settings 单点 pin
        assertTrue(neoBuild.contains("id(\"top.wcpe.loom\")"))
        assertTrue(rootSettings.contains("top.wcpe.loom") && rootSettings.contains("1.17.1"))
        // ADR-0026：不再有独立 settings / wrapper / 受控输入文件门
        assertFalse(Files.exists(repositoryRoot().resolve("platform/neoforge/1.20.2/settings.gradle.kts")))
        assertFalse(Files.exists(repositoryRoot().resolve("platform/neoforge/1.20.2/gradlew.bat")))
        assertFalse(rootBuild.contains("prepareNeoForge1202Inputs"))
        assertFalse(rootBuild.contains("verifyNeoForge1202ProductArtifact"))
        assertFalse(rootBuild.contains("verifyNeoForge1202CurrentReport"))
        assertFalse(rootBuild.contains("根构建不会嵌套调用该 Gradle 9.6.1 wrapper"))
        // 受控内部 JAR 经同根构建项目任务产物消费：helper 由 build-conventions.neoforge 单点实现，车道只应用插件
        assertTrue(neoBuild.contains("id(\"build-conventions.neoforge\")"))
        val neoForgeDependencies =
            readRootFile("build-logic/build-conventions/src/main/kotlin/buildconventions/NeoForgeDependencies.kt")
        assertTrue(neoForgeDependencies.contains("fun neoforgeModuleJar(project: Project, projectPath: String)"))
        assertFalse(neoBuild.contains("verifyInternalJars"))
        assertFalse(neoBuild.contains("includeBuild"))
    }

    @Test
    fun `根构建硬要求守护 JVM 25 不再按 JDK 跳过车道`() {
        val settings = readRootFile("settings.gradle.kts")

        // ADR-0026 决策 4：子模块无法按 JDK 条件跳过 → 改为根构建统一 JDK 25
        assertFalse(settings.contains("JavaVersion.current().majorVersion.toInt() < 25"))
        assertFalse(settings.contains("跳过 fabric/26.2"))
        assertTrue(settings.contains("守护 JVM ≥ 25") || settings.contains("JDK 25"))
    }

    @Test
    fun `Paper 26_2 真服门将本轮标识和 Java 25 透传给托管宿主`() {
        val extension =
            readRootFile(
                "build-logic/realserver-acceptance/src/main/kotlin/top/wcpe/mc/mpmt/gradle/realserver/MpmtRealServerAcceptanceExtension.kt",
            )
        val plugin =
            readRootFile(
                "build-logic/realserver-acceptance/src/main/kotlin/top/wcpe/mc/mpmt/gradle/realserver/MpmtRealServerAcceptancePlugin.kt",
            )
        val host =
            readRootFile(
                "build-logic/realserver-acceptance/src/main/kotlin/top/wcpe/mc/mpmt/gradle/realserver/PaperHostService.kt",
            )
        val bukkitBuild = readRootFile("platform/bukkit/26.2/build.gradle.kts")
        val rootBuild = readRootFile("build.gradle.kts")

        assertTrue(extension.contains("paperJavaVersion"))
        assertTrue(extension.contains("acceptanceRunId"))
        assertTrue(extension.contains("acceptanceStartEpochMs"))
        assertTrue(extension.contains("acceptanceClientProductJar"))
        assertTrue(extension.contains("acceptanceClientAcceptanceJar"))
        assertTrue(plugin.contains("languageVersion.set(ext.paperJavaVersion.map(JavaLanguageVersion::of))"))
        assertTrue(plugin.contains("parameters.acceptanceRunId.set(ext.acceptanceRunId)"))
        assertTrue(plugin.contains("parameters.acceptanceStartEpochMs.set(ext.acceptanceStartEpochMs)"))
        assertTrue(plugin.contains("parameters.acceptanceClientProductJar.set(ext.acceptanceClientProductJar)"))
        assertTrue(plugin.contains("target.tasks.register(\"ensurePaperRealServerHost\")"))
        assertTrue(plugin.contains("mpmt.realserver.waitForReport"))
        assertTrue(plugin.contains("findByName(\"ensurePaperRealServerHost\") == null"))
        assertTrue(plugin.contains("target.path.replace(':', '_')"))
        assertTrue(plugin.contains("if (clientTaskExists)"))
        assertTrue(host.contains("-Dmpmt.acceptance.runId="))
        assertTrue(host.contains("-Dmpmt.acceptance.startEpochMs="))
        assertTrue(host.contains("-Dmpmt.acceptance.artifact.client-product="))
        assertTrue(bukkitBuild.contains("server-report-${'$'}{matrix.lowercase()}.txt"))
        assertTrue(bukkitBuild.contains("paperJavaVersion.set(compilerJavaVersion)"))
        assertTrue(bukkitBuild.contains("acceptanceClientProductJar.set(fabric262Product)"))
        assertTrue(rootBuild.contains("top.wcpe.mc.mpmt.realserver-report-gate"))
        assertTrue(rootBuild.contains("verifyRealServerReportsStrict"))
    }

    @Test
    fun `真服门使用严格报告校验器与本轮实际制品`() {
        val rootBuild = readRootFile("build.gradle.kts")
        val gate =
            readRootFile(
                "build-logic/realserver-acceptance/src/main/kotlin/top/wcpe/mc/mpmt/gradle/realserver/RealServerReportGatePlugin.kt",
            )
        val forgeRuntimeProperty = "mpmt.acceptance.forge.serverRuntime"
        val forgeRuntimeExpression = "required(project, \"$forgeRuntimeProperty\")"

        assertTrue(rootBuild.contains("verifyRealServerReportsStrict"))
        assertTrue(gate.contains("RealServerReportValidator.verify"))
        assertTrue(gate.contains(forgeRuntimeProperty))
        assertTrue(gate.contains("File($forgeRuntimeExpression)"))
        assertFalse(gate.contains("artifact(project.rootDir, $forgeRuntimeExpression)"))
        assertTrue(gate.contains("paper-26.2-71.jar"))
        assertTrue(gate.contains("start"))
    }

    @Test
    fun `严格报告校验在三个车道完成后执行`() {
        val rootBuild = readRootFile("build.gradle.kts")
        val ordering = block(rootBuild, "tasks.named(\"verifyRealServerReportsStrict\")")

        assertTrue(ordering.contains("mustRunAfter("))
        assertTrue(ordering.contains("runRealServerAcceptanceBukkit262"))
        assertTrue(ordering.contains("runRealServerAcceptanceFabric262"))
        assertTrue(ordering.contains("runRealServerAcceptanceForge262"))
    }

    @Test
    fun `Forge 26_2 接入静态质量门且装配单一真源`() {
        val forgeBuild = readRootFile("platform/forge/26.2/build.gradle.kts")
        val qualityPlugin =
            readRootFile("build-logic/build-conventions/src/main/kotlin/buildconventions/QualityConventionPlugin.kt")
        val acceptanceBuild = readRootFile("build-logic/realserver-acceptance/build.gradle.kts")

        // 车道侧：只声明"接入质量门 + 本车道偏离项"，工具链装配不再重复
        assertTrue(forgeBuild.contains("id(\"build-conventions.quality\")"))
        assertTrue(forgeBuild.contains("pmdToolVersion.set(\"7.16.0\")"))
        assertTrue(forgeBuild.contains("spotbugsToolVersion.set(\"4.9.8\")"))
        assertFalse(forgeBuild.contains("apply(plugin = \"checkstyle\")"))
        assertFalse(forgeBuild.contains("findsecbugs-plugin"))
        // 静态质量任务仍挂在车道门禁上，且不得与 check/verifyPackaging 成环
        assertTrue(forgeBuild.contains("staticQualityTasks"))
        assertFalse(forgeBuild.contains("dependsOn(check, verifyPackaging)"))
        assertFalse(forgeBuild.contains("dependsOn(\"check\", \"verifyPackaging\")"))
        // 装配单一真源：工具链、安全插件与覆盖率底线在约定插件内
        assertTrue(qualityPlugin.contains("project.pluginManager.apply(\"checkstyle\")"))
        assertTrue(qualityPlugin.contains("project.pluginManager.apply(\"pmd\")"))
        assertTrue(qualityPlugin.contains("project.pluginManager.apply(\"com.github.spotbugs\")"))
        assertTrue(qualityPlugin.contains("findsecbugs-plugin"))
        assertTrue(qualityPlugin.contains("\"0.70\""))
        assertTrue(acceptanceBuild.contains("org.jlleitschuh.gradle.ktlint"))
        assertTrue(acceptanceBuild.contains("io.gitlab.arturbosch.detekt"))
        assertTrue(acceptanceBuild.contains("org.jetbrains.kotlinx.kover"))
        assertTrue(acceptanceBuild.contains("config/detekt/baseline.xml"))
    }

    @Test
    fun `mc testkit 指南使用当前 Bukkit 工程与产品产物路径`() {
        val guide = readRootFile("e2e/README.md")

        assertTrue(guide.contains(":platform:bukkit:1.20.1:shadowJar"))
        assertTrue(guide.contains("platform/bukkit/1.20.1/build/libs/mpmt-bukkit-1.20.1-<version>.jar"))
        assertFalse(guide.contains(":platform-bukkit:server-1.20.1:shadowJar"))
        assertFalse(guide.contains("${'$'}PWD/platform-bukkit/build/libs/"))
    }

    private fun readRootFile(name: String): String =
        Files.readString(repositoryRoot().resolve(name), StandardCharsets.UTF_8)

    private fun repositoryRoot(): Path = Path.of(System.getProperty("user.dir")).parent.parent

    private fun block(build: String, marker: String): String {
        val start = build.indexOf(marker)
        require(start >= 0) { "缺少构建任务：$marker" }
        val next = build.indexOf("tasks.register(", start + marker.length)
        return if (next < 0) build.substring(start) else build.substring(start, next)
    }
}
