package buildconventions

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

/** bukkit 验收源集名：四条车道一致，源码在 `acceptance/src/main/{java,resources}` 与生成常量目录。 */
internal const val BUKKIT_ACCEPTANCE_SOURCE_SET = "acceptance"

/** 仅 1.20.1 车道使用的验收默认轨契约测试源集名。 */
internal const val BUKKIT_ACCEPTANCE_TEST_SOURCE_SET = "acceptanceTest"

/** 验收默认轨契约测试任务名（仅 1.20.1 车道注册）。 */
internal const val BUKKIT_ACCEPTANCE_CONTRACT_TEST_TASK = "acceptanceContractTest"

/** 源集容器。 */
internal fun bukkitSourceSets(project: Project): SourceSetContainer =
    project.extensions.getByType(SourceSetContainer::class.java)

/** main 源集。 */
internal fun bukkitMainSourceSet(project: Project): SourceSet = bukkitSourceSets(project).getByName("main")

/** 验收源集（由 [registerBukkitAcceptanceSourceSet] 在插件 apply 期创建）。 */
internal fun bukkitAcceptanceSourceSet(project: Project): SourceSet =
    bukkitSourceSets(project).getByName(BUKKIT_ACCEPTANCE_SOURCE_SET)

/**
 * 创建验收源集。
 *
 * 必须在插件 apply 期完成：`build-conventions.platform` 的 afterEvaluate 与车道脚本随后的
 * `platformLane { }` / 依赖声明都要能按名取到它，源集创建时机不得晚于它们。
 */
internal fun registerBukkitAcceptanceSourceSet(project: Project) {
    bukkitSourceSets(project).create(BUKKIT_ACCEPTANCE_SOURCE_SET)
}

/**
 * 创建 1.20.1 车道的 `acceptanceTest` 源集，并让其测试配置继承 `test` 的两条配置。
 *
 * 类路径继承表达式与原车道逐项一致，且必须在 [inheritBukkitAcceptanceMainClasspath] 之前执行——
 * 原实现同样在验收源集继承 main 之前取 `acceptance.compileClasspath/runtimeClasspath`。
 */
internal fun registerBukkitAcceptanceTestSourceSet(
    project: Project,
    lane: BukkitLaneExtension,
    acceptance: SourceSet,
): SourceSet? {
    if (!lane.acceptanceTestSourceSet.get()) {
        return null
    }
    val main = bukkitMainSourceSet(project)
    val acceptanceTest =
        bukkitSourceSets(project).create(BUKKIT_ACCEPTANCE_TEST_SOURCE_SET) {
            compileClasspath += acceptance.output + acceptance.compileClasspath + main.output
            runtimeClasspath += output + acceptance.runtimeClasspath + compileClasspath
        }
    project.configurations.getByName("${BUKKIT_ACCEPTANCE_TEST_SOURCE_SET}Implementation")
        .extendsFrom(project.configurations.getByName("testImplementation"))
    project.configurations.getByName("${BUKKIT_ACCEPTANCE_TEST_SOURCE_SET}RuntimeOnly")
        .extendsFrom(project.configurations.getByName("testRuntimeOnly"))
    return acceptanceTest
}

/** 验收源集继承 main 的类路径（编译与运行），与原车道的 `+=` 表达式逐字一致。 */
internal fun inheritBukkitAcceptanceMainClasspath(project: Project, acceptance: SourceSet) {
    val main = bukkitMainSourceSet(project)
    acceptance.compileClasspath += main.output + main.compileClasspath
    acceptance.runtimeClasspath += main.output + main.runtimeClasspath
}
