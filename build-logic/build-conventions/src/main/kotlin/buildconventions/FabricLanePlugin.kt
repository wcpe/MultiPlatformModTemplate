package buildconventions

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * fabric 车道公共层：车道脚本 `plugins { id("build-conventions.fabric") }` 后只保留"参数与差异"。
 *
 * 集中承担三条 fabric 车道重复的 loader 特有层：
 * - gametest 验收接入层的源集、依赖接线与构建期编译（[registerGametestSourceSet]）
 * - Loom 运行配置（[registerLoomRuns]）与 run 任务的验收元数据注入（[configureAcceptanceRunTasks]）
 * - 模拟服一键门禁（[registerSimulatorAcceptanceGate]）
 * - `fabric.mod.json` 占位展开（[expandModMetadata]）与单测的版本/验收系统属性（[configureGametestTestTask]）
 *
 * 车道的"参数"经 [FabricLaneExtension] 声明；差异项（矩阵 JDK 环境变量、26.2 无 remap 等）同样以属性表达。
 */
class FabricLanePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val lane = project.extensions.create("fabricLane", FabricLaneExtension::class.java)
        applyConventions(project, lane)
        registerGametestSourceSet(project)
        // 车道的 `fabricLane { … }` 块晚于插件 apply，故读取配置值的接线统一在 afterEvaluate 完成。
        project.afterEvaluate {
            val gametest = gametestSourceSet(project)
            registerLoomRuns(project, lane, gametest)
            configureAcceptanceRunTasks(project, lane)
            registerSimulatorAcceptanceGate(project, lane)
            expandModMetadata(project, lane)
            configureGametestTestTask(project, lane)
        }
    }

    /** 公共约定：车道不写即为这些值。 */
    private fun applyConventions(project: Project, lane: FabricLaneExtension) {
        lane.productTaskName.convention(PRODUCT_TASK_REMAP_JAR)
        lane.simulatorReport.convention(project.layout.buildDirectory.file("acceptance/sim-report.txt"))
        lane.acceptanceReport.convention(project.layout.buildDirectory.file("acceptance/server-report.txt"))
        lane.acceptanceServerAddress.convention(
            project.providers
                .gradleProperty(ACCEPTANCE_SERVER_PROPERTY)
                .orElse(DEFAULT_ACCEPTANCE_SERVER_ADDRESS),
        )
        lane.matrixJavaHomeEnvironment.convention("")
        lane.acceptanceServerCompilesGametest.convention(true)
        lane.acceptanceClientExposesServerProperty.convention(true)
        lane.acceptanceServerUsesOverriddenReport.convention(false)
    }

    private companion object {
        /** 默认产品任务：remap 后的最终 jar。 */
        const val PRODUCT_TASK_REMAP_JAR = "remapJar"

        /** 默认验收服务端地址：对齐 `run/server.properties` 的 server-port=25571。 */
        const val DEFAULT_ACCEPTANCE_SERVER_ADDRESS = "127.0.0.1:25571"

        /** 验收客户端连接地址的覆盖属性（插件化前各车道直接读 `-Pmpmt.acceptance.server`）。 */
        const val ACCEPTANCE_SERVER_PROPERTY = "mpmt.acceptance.server"
    }
}
