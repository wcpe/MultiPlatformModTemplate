package buildconventions

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.SourceSet
import org.gradle.jvm.tasks.Jar

/** 产品 jar 需 shade 的内部产物：顺序决定 shadowBundle 的条目顺序，与原车道声明一致。 */
private val NEOFORGE_PRODUCT_INTERNAL_PROJECTS =
    listOf(
        ":core:domain",
        ":core:runtime",
        ":core:protocol",
        ":core:spi",
        ":core:server",
        ":core:client",
        ":platform:neoforge:neoforge-api",
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
 * 共享模块按工程路径取 jar 任务产物（FileCollection，自带任务依赖；文件输入、不引入传递依赖）。
 *
 * 用 `withType<Jar>().matching` 惰性取任务：`named("jar")` 会在本项目先于生产者配置时立即抛
 * UnknownTaskException（子模块按路径序配置，`:platform:neoforge:1.20.2` 先于 `:platform:neoforge:neoforge-api`），
 * 故不可用。
 */
internal fun neoforgeModuleJar(project: Project, projectPath: String): FileCollection =
    project.files(project.project(projectPath).tasks.withType(Jar::class.java).matching { it.name == "jar" })

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
        dependencies.add(NEOFORGE_SHADOW_BUNDLE_CONFIGURATION, moduleJar)
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
    dependencies.add(NEOFORGE_ACCEPTANCE_SHADOW_BUNDLE_CONFIGURATION, acceptanceCoreJar)
    dependencies.add(acceptance.compileOnlyConfigurationName, neoforgeModuleJar(project, NEOFORGE_PROTOCOL_PROJECT))
}
