package buildconventions

import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSet

/** 产品 jar 需 shade 的内部产物：顺序决定 shadowBundle 的条目顺序，与原车道声明一致。 */
private val NEOFORGE_PRODUCT_INTERNAL_PROJECTS =
    listOf(
        ":core:kernel",
        ":core:runtime",
        ":core:protocol",
        ":core:spi",
        ":core:server",
        ":core:client",
    )

/** 平台无关验收核心（控制协议 / 协调 / GameTest 框架 / 报告）：验收 jar 独有，shade 进去。 */
private const val NEOFORGE_ACCEPTANCE_CORE_PROJECT: String = ":modules:acceptance"

/** protocol（编 HUD 包用）：仅验收编译期可见，运行期由产品 mod 提供，绝不打进验收 jar（防 split package）。 */
private const val NEOFORGE_PROTOCOL_PROJECT: String = ":core:protocol"

/** 单测 JUnit 版本（与其它车道同一套）。 */
private const val NEOFORGE_JUNIT_BOM: String = "org.junit:junit-bom:5.10.3"
private const val NEOFORGE_JUNIT_JUPITER: String = "org.junit.jupiter:junit-jupiter"
private const val NEOFORGE_JUNIT_LAUNCHER: String = "org.junit.platform:junit-platform-launcher"

/** 需 shade 并 relocate 的第三方运行期依赖（版本由车道参数给出，ADR-0012）。 */
private const val NEOFORGE_SNAKEYAML_MODULE: String = "org.yaml:snakeyaml"

/**
 * 共享模块按工程路径取该模块产物（非传递项目依赖）。
 *
 * 必须用项目依赖而非 `files(任务集合)`：文件集合会在消费方配置期就快照求值，
 * 在按需配置（`--configure-on-demand`）下生产者项目尚未配置，结果为空且不报错，
 * 整条内部类路径随之静默消失；项目依赖惰性求值，且由 Gradle 保证各 jar 任务顺序。
 */
internal fun neoforgeModuleJar(project: Project, projectPath: String): ProjectDependency =
    (project.dependencies.project(mapOf("path" to projectPath)) as ProjectDependency)
        .apply { isTransitive = false }

/**
 * 依赖接线：产品 shade 闭包、单测与验收源集依赖。
 *
 * 每个配置内的声明顺序与原车道脚本逐项对应（内部产物与第三方同进 `implementation` 与 `shadowBundle`），
 * 故产物条目顺序不变。
 *
 * arch-loom 的三段式平台坐标（`minecraft` / `mappings` / `neoForge`）仍在车道脚本里声明：loom 在自身的
 * afterEvaluate 立刻校验这三个配置非空，晚于该时点的声明会让 userdev 管线直接失败。
 */
internal fun registerNeoForgeDependencies(project: Project, lane: NeoForgeLaneExtension, acceptance: SourceSet) {
    registerNeoForgeProductDependencies(project, lane)
    registerNeoForgeTestDependencies(project)
    registerNeoForgeAcceptanceDependencies(project, acceptance)
}

/** 产品依赖：内部产物（文件输入没有 POM 传递关系，故显式列出完整闭包并一并 shade）+ relocate 的第三方。 */
private fun registerNeoForgeProductDependencies(project: Project, lane: NeoForgeLaneExtension) {
    val dependencies = project.dependencies
    NEOFORGE_PRODUCT_INTERNAL_PROJECTS.forEach { projectPath ->
        val moduleJar = neoforgeModuleJar(project, projectPath)
        dependencies.add("implementation", moduleJar)
        dependencies.add(NEOFORGE_SHADOW_BUNDLE_CONFIGURATION, moduleJar.copy())
    }
    // 功能域（L0 玩法逻辑）：新增域无需改上面的固定清单——mpmtDomains() 动态覆盖（ADR-0028）。
    // 必须与内部产物同样处理：既进 implementation，也进 shadowBundle，否则域不会被打进产品 jar。
    project.mpmtDomains().forEach { domain ->
        val moduleJar = neoforgeModuleJar(project, domain.path)
        dependencies.add("implementation", moduleJar)
        dependencies.add(NEOFORGE_SHADOW_BUNDLE_CONFIGURATION, moduleJar.copy())
    }
    val snakeyaml = "$NEOFORGE_SNAKEYAML_MODULE:${lane.snakeyamlVersion.get()}"
    dependencies.add("implementation", snakeyaml)
    dependencies.add(NEOFORGE_SHADOW_BUNDLE_CONFIGURATION, snakeyaml)
}

/** 单测依赖：JUnit 5 一套 + 验收核心（契约复用），与其它车道同构。 */
private fun registerNeoForgeTestDependencies(project: Project) {
    val dependencies = project.dependencies
    dependencies.add("testImplementation", dependencies.platform(NEOFORGE_JUNIT_BOM))
    dependencies.add("testImplementation", NEOFORGE_JUNIT_JUPITER)
    dependencies.add("testImplementation", neoforgeModuleJar(project, NEOFORGE_ACCEPTANCE_CORE_PROJECT))
    dependencies.add("testRuntimeOnly", NEOFORGE_JUNIT_LAUNCHER)
}

/**
 * 验收依赖：验收核心只进验收 jar（产品 mod jar 不含它）；protocol 仅编译期可见
 * （运行期由产品 mod 提供，绝不打进验收 jar，防 FML 模块层 split package）。
 */
private fun registerNeoForgeAcceptanceDependencies(project: Project, acceptance: SourceSet) {
    val dependencies = project.dependencies
    val acceptanceCoreJar = neoforgeModuleJar(project, NEOFORGE_ACCEPTANCE_CORE_PROJECT)
    dependencies.add(acceptance.implementationConfigurationName, acceptanceCoreJar)
    dependencies.add(NEOFORGE_ACCEPTANCE_SHADOW_BUNDLE_CONFIGURATION, acceptanceCoreJar.copy())
    dependencies.add(acceptance.compileOnlyConfigurationName, neoforgeModuleJar(project, NEOFORGE_PROTOCOL_PROJECT))
    // 验收源集要直接构造域内组件（如 PlatformCapabilityExample），故须拿到全部域模块。
    // 域模块经 ADR-0030 拆成多模块后不再由单一坐标传递，必须显式登记。
    project.mpmtDomains().forEach { domain ->
        dependencies.add(acceptance.implementationConfigurationName, domain)
    }
}
