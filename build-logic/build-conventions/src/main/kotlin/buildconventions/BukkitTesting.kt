package buildconventions

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File

/** 验收 `plugin.yml`（展开后）相对构建目录的路径：单测据此定位验收元数据。 */
private const val BUKKIT_ACCEPTANCE_METADATA_PATH = "resources/acceptance/plugin.yml"

/** `test` / `acceptanceContractTest` 的公共启动配置。 */
internal fun configureBukkitTestTasks(
    project: Project,
    lane: BukkitLaneExtension,
    acceptance: SourceSet,
    acceptanceTest: SourceSet?,
) {
    val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
    project.tasks.named("test", Test::class.java).configure {
        useJUnitPlatform()
        dependsOn(acceptance.processResourcesTaskName)
        javaLauncher.set(bukkitTestLauncher(toolchains, lane))
        bukkitTestSystemProperties(project, lane).forEach { (key, value) -> systemProperty(key, value) }
    }
    acceptanceTest?.let { registerBukkitAcceptanceContractTest(project, lane, it, toolchains) }
}

/** 单测的版本与验收系统属性：键名与取值口径与原车道逐字一致。 */
private fun bukkitTestSystemProperties(project: Project, lane: BukkitLaneExtension): Map<String, Any> {
    val mcVersion = lane.mcVersion.get()
    return mapOf(
        "mpmt.test.minecraftVersion" to mcVersion,
        "mpmt.test.javaVersion" to lane.testJavaVersion.get(),
        "mpmt.test.archiveName" to "$BUKKIT_PRODUCT_JAR_BASE_NAME_PREFIX-$mcVersion",
        "mpmt.test.productChannel" to lane.productChannel.get(),
        "mpmt.test.acceptanceChannel" to lane.acceptanceChannel.get(),
        "mpmt.test.regionSchedulerClass" to lane.regionSchedulerClass.get(),
        "mpmt.test.apiVersion" to lane.apiVersion.get(),
        "mpmt.test.foliaMetadata" to lane.foliaSupported.get(),
        "mpmt.test.acceptanceMetadata" to bukkitAcceptanceMetadataFile(project).absolutePath,
    )
}

/** 1.20.1 车道的验收默认轨契约测试：跑 `acceptanceTest` 源集，不依赖产品单测。 */
private fun registerBukkitAcceptanceContractTest(
    project: Project,
    lane: BukkitLaneExtension,
    acceptanceTest: SourceSet,
    toolchains: JavaToolchainService,
) {
    project.tasks.register(BUKKIT_ACCEPTANCE_CONTRACT_TEST_TASK, Test::class.java) {
        group = "verification"
        description = "运行 Bukkit acceptance v2 与完整默认轨场景契约测试"
        testClassesDirs = acceptanceTest.output.classesDirs
        classpath = acceptanceTest.runtimeClasspath
        useJUnitPlatform()
        javaLauncher.set(bukkitTestLauncher(toolchains, lane))
    }
}

/** 单测与验收契约测试统一用车道目标 Java 版本启动。 */
private fun bukkitTestLauncher(toolchains: JavaToolchainService, lane: BukkitLaneExtension) =
    toolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(lane.targetJavaVersion.get()))
    }

/** 展开后的验收 `plugin.yml`（`processAcceptanceResources` 输出）。 */
private fun bukkitAcceptanceMetadataFile(project: Project): File =
    project.layout.buildDirectory.file(BUKKIT_ACCEPTANCE_METADATA_PATH).get().asFile
