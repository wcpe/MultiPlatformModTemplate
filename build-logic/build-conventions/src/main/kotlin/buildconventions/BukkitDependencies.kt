package buildconventions

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.tasks.SourceSet

/** 产品入口依赖：公共胶水始终并入，modern 模块仅在车道声明了现代适配器时并入。 */
private const val BUKKIT_COMMON_PROJECT = ":platform:bukkit:common"
private const val BUKKIT_MODERN_PROJECT = ":platform:bukkit:modern"

/** 验收驱动与核心链路依赖：顺序决定验收 jar 的文件顺序，与原车道声明一致。 */
private val BUKKIT_ACCEPTANCE_RUNTIME_PROJECTS =
    listOf(":modules:acceptance", ":core:protocol", ":core:server", ":core:client")

/** 单测 JUnit 三件套坐标（四条车道同源）。 */
private const val BUKKIT_JUNIT_BOM_COORDINATE = "org.junit:junit-bom:5.10.3"
private const val BUKKIT_JUNIT_JUPITER_COORDINATE = "org.junit.jupiter:junit-jupiter"
private const val BUKKIT_JUNIT_LAUNCHER_COORDINATE = "org.junit.platform:junit-platform-launcher"

/** 1.12.2 车道需排除的废弃传递依赖（由本地补丁 jar 顶替）。 */
private const val BUKKIT_BUNGEECORD_CHAT_GROUP = "net.md-5"
private const val BUKKIT_BUNGEECORD_CHAT_MODULE = "bungeecord-chat"

/**
 * 依赖接线：产品依赖、API 坐标与验收源集依赖。
 *
 * 每个配置内的声明顺序与原车道脚本逐项对应（1.12.2 的 bungeecord-chat 补丁 jar、现代车道的 modern 模块
 * 都是同一批表达式里的条件项），故打包产物的条目顺序不变；车道级偏离项（1.20.1 的 MockBukkit、
 * 26.2 的 adventure 钉版本）仍由车道脚本声明。
 */
internal fun registerBukkitDependencies(project: Project, lane: BukkitLaneExtension, acceptance: SourceSet) {
    project.dependencies.add("implementation", project.project(BUKKIT_COMMON_PROJECT))
    if (lane.modernProduct.get()) {
        project.dependencies.add("implementation", project.project(BUKKIT_MODERN_PROJECT))
    }
    listOf("compileOnly", "testImplementation", acceptance.compileOnlyConfigurationName)
        .forEach { configuration -> registerBukkitApiDependency(project, configuration, lane) }
    registerBukkitJunitDependencies(project)
    bukkitProductEntryProjects(project, lane).forEach { entry ->
        project.dependencies.add(acceptance.compileOnlyConfigurationName, entry)
    }
    BUKKIT_ACCEPTANCE_RUNTIME_PROJECTS.forEach { path ->
        project.dependencies.add(acceptance.implementationConfigurationName, project.project(path))
    }
}

/**
 * API 坐标（编译期依赖，运行期由宿主提供）。
 *
 * 1.12.2 车道的 spigot-api 会拖入已废弃的 bungeecord-chat，故排除之并以本地补丁 jar 顶替。
 */
private fun registerBukkitApiDependency(project: Project, configuration: String, lane: BukkitLaneExtension) {
    val bungeeChatJar = lane.bungeeChatFallbackJar.get()
    project.dependencies.add(configuration, bukkitApiDependency(project, lane.apiCoordinate.get(), bungeeChatJar))
    if (bungeeChatJar.isNotEmpty()) {
        project.dependencies.add(configuration, project.rootProject.files(bungeeChatJar))
    }
}

/** API 模块依赖：1.12.2 车道按需挂上 bungeecord-chat 排除规则。 */
private fun bukkitApiDependency(
    project: Project,
    coordinate: String,
    bungeeChatJar: String,
): ExternalModuleDependency {
    val dependency = project.dependencies.create(coordinate) as ExternalModuleDependency
    if (bungeeChatJar.isNotEmpty()) {
        dependency.exclude(mapOf("group" to BUKKIT_BUNGEECORD_CHAT_GROUP, "module" to BUKKIT_BUNGEECORD_CHAT_MODULE))
    }
    return dependency
}

/** 单测 JUnit 三件套：产品单测与验收契约测试共用同一版本口径。 */
private fun registerBukkitJunitDependencies(project: Project) {
    val dependencies = project.dependencies
    dependencies.add("testImplementation", dependencies.platform(BUKKIT_JUNIT_BOM_COORDINATE))
    dependencies.add("testImplementation", BUKKIT_JUNIT_JUPITER_COORDINATE)
    dependencies.add("testRuntimeOnly", BUKKIT_JUNIT_LAUNCHER_COORDINATE)
}

/** 验收默认轨契约测试依赖：驱动与核心链路 + JUnit 三件套（与原车道声明一致）。 */
internal fun registerBukkitAcceptanceTestDependencies(project: Project, acceptanceTest: SourceSet) {
    val dependencies = project.dependencies
    val implementation = acceptanceTest.implementationConfigurationName
    BUKKIT_ACCEPTANCE_RUNTIME_PROJECTS.forEach { path ->
        dependencies.add(implementation, project.project(path))
    }
    dependencies.add(implementation, dependencies.platform(BUKKIT_JUNIT_BOM_COORDINATE))
    dependencies.add(implementation, BUKKIT_JUNIT_JUPITER_COORDINATE)
    dependencies.add(acceptanceTest.runtimeOnlyConfigurationName, BUKKIT_JUNIT_LAUNCHER_COORDINATE)
}

/** 产品入口工程依赖：公共胶水始终在列，modern 模块按车道声明并入。 */
private fun bukkitProductEntryProjects(project: Project, lane: BukkitLaneExtension): List<Any> =
    listOf(BUKKIT_COMMON_PROJECT, BUKKIT_MODERN_PROJECT)
        .filter { path -> lane.modernProduct.get() || path != BUKKIT_MODERN_PROJECT }
        .map { path -> project.project(path) }
