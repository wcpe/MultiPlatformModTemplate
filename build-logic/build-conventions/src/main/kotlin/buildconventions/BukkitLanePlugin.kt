package buildconventions

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * bukkit 车道公共层：车道脚本 `plugins { id("build-conventions.bukkit") }` 后只保留"参数与差异"。
 *
 * 集中承担四条 bukkit 车道重复的 loader 层：
 * - 验收源集结构（[registerBukkitAcceptanceSourceSet]、[registerBukkitAcceptanceTestSourceSet]）
 *   与依赖接线（[registerBukkitDependencies]）
 * - `plugin.yml` 元数据展开（[expandBukkitPluginMetadata]）、编译工具链与任务属性（[configureBukkitJavaCompilation]）
 * - 打包链路（[configureBukkitArchiveTasks]）与 `build`/`assemble` 门禁接线（[wireBukkitGateTasks]）
 * - 单测属性（[configureBukkitTestTasks]）与 realserver 门禁公共接线（[configureBukkitRealServerAcceptance]）
 *
 * 车道的"参数"经 [BukkitLaneExtension] 声明。不可变契约仍留在车道脚本：
 * 版本与 API 坐标/哈希、`platformLane { }` 验收通道常量、`frozenApiSnapshot` 冻结校验、
 * `verifyPackaging` 断言清单、`runRealServerAcceptance` 判定与文案、任务名与报告路径、`-Pmpmt.*` 属性名。
 *
 * 应用顺序：本插件须晚于 `java` 与 `com.gradleup.shadow`（打包链路要按名取 `jar`/`shadowJar`），
 * 早于 `top.wcpe.mc.mpmt.realserver-acceptance`（车道写入的验收扩展取值要早于其 afterEvaluate 生效）。
 */
class BukkitLanePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val lane = project.extensions.create("bukkitLane", BukkitLaneExtension::class.java)
        applyConventions(lane)
        // 验收源集须在 apply 期创建：build-conventions.platform 的 afterEvaluate 与车道脚本随后的
        // `platformLane { }` / 依赖声明都要能按名取到它。
        registerBukkitAcceptanceSourceSet(project)
        // 车道的 `bukkitLane { … }` 块晚于插件 apply，故读取配置值的接线统一在 afterEvaluate 完成。
        project.afterEvaluate {
            val acceptance = bukkitAcceptanceSourceSet(project)
            val acceptanceTest = registerBukkitAcceptanceTestSourceSet(project, lane, acceptance)
            inheritBukkitAcceptanceMainClasspath(project, acceptance)
            registerBukkitDependencies(project, lane, acceptance)
            acceptanceTest?.let { registerBukkitAcceptanceTestDependencies(project, it) }
            configureBukkitJavaCompilation(project, lane)
            expandBukkitPluginMetadata(project, lane)
            configureBukkitArchiveTasks(project, lane, acceptance)
            configureBukkitTestTasks(project, lane, acceptance, acceptanceTest)
            wireBukkitGateTasks(project, lane, acceptance)
            configureBukkitRealServerAcceptance(project, lane)
            if (lane.managedPaperHost.get()) {
                registerBukkitPaperHostEnsureTask(project)
            }
        }
    }

    /** 公共约定：车道不写即为这些值。 */
    private fun applyConventions(lane: BukkitLaneExtension) {
        lane.compilerJavaVersion.convention(lane.targetJavaVersion)
        lane.releaseTargetVersion.convention(true)
        lane.modernProduct.convention(true)
        lane.acceptanceTestSourceSet.convention(false)
        lane.apiVersion.convention("")
        lane.foliaSupported.convention(false)
        lane.bungeeChatFallbackJar.convention("")
        lane.testJavaVersion.convention(lane.targetJavaVersion.map { it.toString() })
        lane.managedPaperHost.convention(false)
    }
}
