package buildconventions

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet

/** 车道内注册的打包校验任务名（`verifyPackaging` 的断言清单仍由车道脚本声明）。 */
private const val BUKKIT_VERIFY_PACKAGING_TASK = "verifyPackaging"

/** API 冻结校验任务名（由 `frozenApiSnapshot` 注册，车道脚本负责声明 API 坐标与哈希）。 */
private const val BUKKIT_API_FREEZE_TASK = "verifyApiSnapshotFreeze"

/**
 * 门禁接线：`assemble` 依赖打包校验、`build` 依赖产品/验收产物与冻结校验，
 * 验收默认轨契约测试（仅 1.20.1）同时挂在 `build` 与 `check` 上。
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
}
