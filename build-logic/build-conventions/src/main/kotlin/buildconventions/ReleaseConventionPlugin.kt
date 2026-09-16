package buildconventions

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 根工程编排约定插件：id = `build-conventions.release`（应用于根工程，ADR-0027）。
 *
 * 根 `build.gradle.kts` 只保留插件声明、根级坐标/版本与 A 车道 mc-testkit 接线；
 * 以下根侧编排实现在此集中：
 * - 发布聚合：`verifyReleasePackaging` / `buildFabric262` / `collectReleaseArtifacts` / `buildAll`；
 * - 真服门：`runRealServerAcceptance*`（含 262 三车道）与说明入口 `listRealServerLanes`；
 * - 版本矩阵门：`runVersionMatrixRealServerAcceptance` / `verifyVersionMatrixBuild` / `runVersionMatrixGate`。
 *
 * 搬迁只搬实现：任务名与任务路径、依赖图、`-P` 属性名、门禁判定与既有的失败/提示文案逐项不变。
 *
 * 应用顺序要求：须声明在 `top.wcpe.mc.mpmt.realserver-report-gate` 之后——该插件注册
 * `verifyRealServerReportsStrict`，本插件为它接线 `mustRunAfter`。
 */
class ReleaseConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // 版本号唯一来源 = 根目录 VERSION 文件（testing-and-quality §3），与根脚本 group/version 同源。
        val releaseVersion = target.rootProject.file("VERSION").readText().trim()

        val collectReleaseArtifacts = registerReleaseArtifactAggregation(target, releaseVersion)
        val buildFabric262 = registerReleasePackagingGates(target, collectReleaseArtifacts)
        registerRealServerLaneGates(target)
        registerVersionMatrixGates(target)
        registerRealServer262Gates(target, releaseVersion, buildFabric262)
    }
}
