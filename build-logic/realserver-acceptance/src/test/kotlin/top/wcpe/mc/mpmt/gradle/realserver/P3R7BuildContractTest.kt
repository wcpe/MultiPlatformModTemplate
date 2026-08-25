package top.wcpe.mc.mpmt.gradle.realserver

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class P3R7BuildContractTest {
    @Test
    fun `P2 门不混入 26_2 且 R7 有独立三车道入口`() {
        val build = readRootFile("build.gradle.kts")
        val p2Build = block(build, "tasks.register(\"verifyVersionMatrixBuild\")")
        val p2Acceptance = block(build, "tasks.register(\"runP2RealServerAcceptance\")")

        assertFalse(p2Build.contains("26.2"))
        assertFalse(p2Acceptance.contains("262"))
        assertTrue(build.contains("tasks.register(\"runP3R7Build\")"))
        assertTrue(build.contains("tasks.register(\"runP3R7RealServerAcceptance\")"))
        assertTrue(build.contains("runRealServerAcceptanceBukkit262"))
        assertTrue(build.contains("runRealServerAcceptanceFabric262"))
        assertTrue(build.contains("runRealServerAcceptanceForge262"))
    }

    @Test
    fun `发布聚合收集 26_2 产物且 Forge 只提供自有 wrapper 提示`() {
        val build = readRootFile("build.gradle.kts")

        assertTrue(build.contains("platform-fabric-26.2", ignoreCase = false))
        assertTrue(build.contains("mpmt-fabric-26.2-${'$'}version.jar"))
        assertTrue(build.contains("mpmt-forge-26.2-${'$'}version.jar"))
        assertFalse(build.contains("Forge 26.2 产物（可选）"))
        assertTrue(build.contains("platform/forge/26.2 用自有 wrapper 运行 ./gradlew --no-daemon packageArtifacts"))
        assertTrue(build.contains("发布制品缺失"))
    }

    @Test
    fun `Fabric 26_2 只消费受控内部 JAR 且不反向包含根工程`() {
        val settings = readRootFile("platform/fabric/26.2/settings.gradle.kts")
        val build = readRootFile("platform/fabric/26.2/build.gradle.kts")
        val rootBuild = readRootFile("build.gradle.kts")

        assertFalse(settings.contains("includeBuild(\"../../..\")"))
        assertTrue(build.contains("val requiredInternalJars"))
        assertTrue(build.contains("tasks.withType<JavaCompile>().configureEach"))
        assertTrue(build.contains("prepareFabric262Inputs"))
        assertTrue(rootBuild.contains("dependsOn(it.task(\":build\"))"))
        assertFalse(rootBuild.contains("finalizedBy(it.task(\":build\"))"))
        assertFalse(build.contains("tasks.named<RemapJarTask>(\"remapJar\")"))
        assertTrue(build.contains("val configuredAcceptanceReport"))
        assertTrue(build.contains("property(\"mpmt.acceptance.report\", configuredAcceptanceReport)"))
        assertTrue(build.contains("val systemPropertyPrefixes = task.systemProperties.keys.map"))
        assertTrue(build.contains("command += task.systemProperties.map"))
        assertTrue(build.contains("TimeUnit.SECONDS.toNanos(300)"))
    }

    @Test
    fun `Gradle 9 不将 Forge 1_20_1 纳入根复合构建`() {
        val settings = readRootFile("settings.gradle.kts")

        assertTrue(settings.contains("val rootCannotRunForge120"))
        assertTrue(settings.contains("GradleVersion.version(\"9.0\")"))
        assertTrue(settings.contains("!rootCannotRunForge120"))
        assertTrue(settings.contains("请用其独立车道"))
    }

    @Test
    fun `mc testkit 插件有最小范围的本地 Maven 回退`() {
        val settings = readRootFile("settings.gradle.kts")

        assertTrue(settings.contains("mavenLocal {"))
        assertTrue(settings.contains("includeGroup(\"top.wcpe.mc-testkit\")"))
        assertTrue(settings.contains("includeGroup(\"top.wcpe.mc\")"))
    }

    @Test
    fun `NeoForge 1_20_2 保持 Gradle 8 独立车道且根只校验产物与报告`() {
        val rootBuild = readRootFile("build.gradle.kts")
        val rootSettings = readRootFile("settings.gradle.kts")
        val neoSettings = readRootFile("platform/neoforge/1.20.2/settings.gradle.kts")
        val neoBuild = readRootFile("platform/neoforge/1.20.2/build.gradle.kts")
        val wrapper = readRootFile("platform/neoforge/1.20.2/gradle/wrapper/gradle-wrapper.properties")

        assertFalse(rootSettings.contains("includeBuild(\"platform/neoforge/1.20.2\")"))
        assertTrue(rootSettings.contains("根 Gradle 不加载 NeoForge 1.20.2"))
        assertFalse(neoSettings.contains("includeBuild("))
        assertTrue(wrapper.contains("gradle-8.14.5-bin.zip"))
        assertTrue(Files.isRegularFile(repositoryRoot().resolve("platform/neoforge/1.20.2/gradlew.bat")))
        assertTrue(rootBuild.contains("val prepareNeoForge1202Inputs"))
        assertTrue(rootBuild.contains("val verifyNeoForge1202ProductArtifact"))
        assertTrue(rootBuild.contains("val verifyNeoForge1202CurrentReport"))
        assertFalse(rootBuild.contains("dependsOnIncludedIfPresent(\"platform-neoforge\""))
        assertTrue(rootBuild.contains("cd platform/neoforge/1.20.2; .\\\\gradlew.bat --no-daemon packageArtifacts"))
        assertTrue(neoBuild.contains("val requiredInternalJars: List<FileCollection>"))
        assertTrue(neoBuild.contains("val verifyInternalJars"))
        assertFalse(neoBuild.contains("includeBuild(\"../../..\")"))
    }

    @Test
    fun `Paper 26_2 R7 将本轮标识和 Java 25 透传给托管宿主`() {
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
        assertTrue(rootBuild.contains("top.wcpe.mc.mpmt.p3-r7-report-gate"))
        assertTrue(rootBuild.contains("verifyP3R7ReportsStrict"))
    }

    @Test
    fun `P3 R7 门使用严格报告校验器与本轮实际制品`() {
        val rootBuild = readRootFile("build.gradle.kts")
        val gate =
            readRootFile(
                "build-logic/realserver-acceptance/src/main/kotlin/top/wcpe/mc/mpmt/gradle/realserver/P3R7ReportGatePlugin.kt",
            )
        val forgeRuntimeProperty = "mpmt.acceptance.forge.serverRuntime"
        val forgeRuntimeExpression = "required(project, \"$forgeRuntimeProperty\")"

        assertTrue(rootBuild.contains("verifyP3R7ReportsStrict"))
        assertTrue(gate.contains("P3R7ReportValidator.verify"))
        assertTrue(gate.contains(forgeRuntimeProperty))
        assertTrue(gate.contains("File($forgeRuntimeExpression)"))
        assertFalse(gate.contains("artifact(project.rootDir, $forgeRuntimeExpression)"))
        assertTrue(gate.contains("paper-26.2-71.jar"))
        assertTrue(gate.contains("start"))
    }

    @Test
    fun `P3 R7 严格报告校验在三个车道完成后执行`() {
        val rootBuild = readRootFile("build.gradle.kts")
        val ordering = block(rootBuild, "tasks.named(\"verifyP3R7ReportsStrict\")")

        assertTrue(ordering.contains("mustRunAfter("))
        assertTrue(ordering.contains("runRealServerAcceptanceBukkit262"))
        assertTrue(ordering.contains("runRealServerAcceptanceFabric262"))
        assertTrue(ordering.contains("runRealServerAcceptanceForge262"))
    }

    @Test
    fun `Forge 26_2 与真服编排接入静态质量门`() {
        val forgeBuild = readRootFile("platform/forge/26.2/build.gradle")
        val acceptanceBuild = readRootFile("build-logic/realserver-acceptance/build.gradle.kts")

        assertTrue(forgeBuild.contains("com.github.spotbugs"))
        assertTrue(forgeBuild.contains("apply plugin: 'checkstyle'"))
        assertTrue(forgeBuild.contains("apply plugin: 'pmd'"))
        assertTrue(forgeBuild.contains("findsecbugs-plugin"))
        assertTrue(forgeBuild.contains("def staticQualityTasks"))
        assertFalse(forgeBuild.contains("dependsOn check, verifyPackaging"))
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
