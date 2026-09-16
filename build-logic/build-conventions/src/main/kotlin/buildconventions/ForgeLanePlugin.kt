package buildconventions

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * forge 车道公共层：车道脚本 `plugins { id("build-conventions.forge") }` 后只保留"参数与差异"。
 *
 * 集中承担四条 forge 车道重复的 loader 层：
 * - 编译/资源公共接线（[configureForgeJavaCompilation]、[expandForgeModMetadata]、[configureForgeUnitTests]）
 * - 验收 jar 与 FG 时代 reobf 兼容路径（[registerForgeAcceptanceJar]、[registerForgeReobfCopyCompat]）
 * - dev SecureJar 嵌入链与完整性校验（[registerForgeDevModOutputs]、[verifyForgeDevSecureJarOutputs]）
 * - 验收报告门与契约测试（[registerForgeAcceptanceReportGate]、[registerForgeContractTest]）
 * - 现代车道打包校验与出口（[registerForgeModernPackagingVerification]、[registerForgePackageArtifacts]）
 *
 * 车道的"参数"经 [ForgeLaneExtension] 声明；`forgeLane { }` 块晚于插件 apply，
 * 故读取参数值的接线统一在 afterEvaluate 完成。
 *
 * 不可变契约仍留在车道脚本：任务名与任务路径、loom run 配置与 `-Pmpmt.acceptance.*` 属性名、
 * 依赖坐标与仓库、verifyPackaging 断言清单（1.20.1）、真服编排（26.2 单进程编排与托管宿主编排）。
 */
class ForgeLanePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val lane = project.extensions.create(FORGE_LANE_EXTENSION_NAME, ForgeLaneExtension::class.java)
        applyConventions(lane)
        project.afterEvaluate {
            configureForgeJavaCompilation(project)
            expandForgeModMetadata(project, lane)
            configureForgeUnitTests(project, lane)
            registerForgeReobfCopyCompat(project, lane)
            if (lane.devModOutputs.get()) {
                registerForgeDevModOutputs(project, lane)
            }
            if (lane.acceptanceReportGate.get()) {
                registerForgeAcceptanceReportGate(project, lane)
                registerForgeAcceptanceRecipe(project, lane)
            }
            if (lane.modernPackagingVerification.get()) {
                registerForgeModernPackagingVerification(project, lane)
                registerForgePackageArtifacts(project, lane)
            }
            registerForgeContractTest(project, lane)
        }
    }

    /** 公共约定：车道不写即为这些值。 */
    private fun applyConventions(lane: ForgeLaneExtension) {
        lane.modMetadataResource.convention("META-INF/mods.toml")
        lane.deduplicateProcessedResources.convention(false)
        lane.archiveExcludes.convention(FORGE_ARCHIVE_EXCLUDES)
        lane.productModClass.convention("")
        lane.acceptanceModClass.convention("")
        lane.acceptanceExcludes.convention(emptyList())
        lane.acceptanceJarVersionedDevLibs.convention(false)
        lane.devModOutputs.convention(false)
        lane.acceptanceReportGate.convention(false)
        lane.acceptanceGateClientHint.convention("runAcceptanceClient")
        lane.modernPackagingVerification.convention(false)
        lane.packageArtifactsDependsOn.convention(emptyList())
        lane.unitTestMetadata.convention(false)
        lane.contractTestMainClass.convention("")
        lane.contractTestDescription.convention(lane.laneLabel.map { "校验 $it 冻结矩阵、双 JAR 隔离与 class major" })
        lane.contractTestDependsOn.convention(emptyList())
        lane.contractTestProperties.convention(emptyMap())
    }

    private companion object {
        /** 车道参数扩展名（与其它 loader 约定插件同构：`fabricLane` / `bukkitLane` / `spongeLane`）。 */
        const val FORGE_LANE_EXTENSION_NAME = "forgeLane"
    }
}
