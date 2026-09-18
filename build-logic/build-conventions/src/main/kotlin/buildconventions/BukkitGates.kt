package buildconventions

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet

/** 车道内注册的打包校验任务名（`verifyPackaging` 的断言清单仍由车道脚本声明）。 */
private const val BUKKIT_VERIFY_PACKAGING_TASK = "verifyPackaging"

/** API 冻结校验任务名（由 `frozenApiSnapshot` 注册，车道脚本负责声明 API 坐标与哈希）。 */
private const val BUKKIT_API_FREEZE_TASK = "verifyApiSnapshotFreeze"

/** realserver 验收编排插件 id（本插件早于它应用，故其任务须延后接线）。 */
private const val REALSERVER_ACCEPTANCE_PLUGIN_ID = "top.wcpe.mc.mpmt.realserver-acceptance"

/**
 * 门禁接线：`assemble` 依赖打包校验、`build` 依赖产品/验收产物与冻结校验，
 * 验收默认轨契约测试（仅 1.20.1）同时挂在 `build` 与 `check` 上；
 * realserver 门禁在 `realserver-acceptance` 插件应用后补接线（该插件晚于本插件）。
 */
internal fun wireBukkitGateTasks(project: Project, lane: BukkitLaneExtension, acceptance: SourceSet) {
    val withAcceptanceTest = lane.acceptanceTestSourceSet.get()
    project.tasks.named("assemble").configure { dependsOn(BUKKIT_VERIFY_PACKAGING_TASK) }
    project.tasks.named("build").configure {
        dependsOn(BUKKIT_PRODUCT_JAR_TASK, acceptance.classesTaskName)
        dependsOn(BUKKIT_VERIFY_PACKAGING_TASK, BUKKIT_API_FREEZE_TASK)
        if (withAcceptanceTest) {
            dependsOn(BUKKIT_ACCEPTANCE_CONTRACT_TEST_TASK)
        }
    }
    if (withAcceptanceTest) {
        project.tasks.named("check").configure { dependsOn(BUKKIT_ACCEPTANCE_CONTRACT_TEST_TASK) }
    }
    // realserver 门禁：真服装载产品 jar 与验收驱动 jar，故须先产出两者；
    // 报告判定由 realserver-acceptance 插件注册的 verifyMpmtAcceptanceReport 承担。
    // 描述里的两个提示按车道开关拼出（1.20.1 车道原本逐字如此，其余车道无提示）。
    project.pluginManager.withPlugin(REALSERVER_ACCEPTANCE_PLUGIN_ID) {
        val hostHint =
            if (lane.managedPaperHost.get()) {
                "（-Pmpmt.realserver.autoHost=true 时接线 PaperHostService；" +
                    "-Pmpmt.acceptance.matrix=SCHEDULER 时读 server-report-scheduler.txt）"
            } else {
                ""
            }
        project.tasks.named("runRealServerAcceptance").configure {
            group = "verification"
            description = "Bukkit ${lane.mcVersion.get()} realserver 门禁$hostHint"
            dependsOn(
                project.tasks.named(BUKKIT_PRODUCT_JAR_TASK),
                project.tasks.named(BUKKIT_ACCEPTANCE_JAR_TASK),
                "verifyMpmtAcceptanceReport",
            )
        }
    }
}
