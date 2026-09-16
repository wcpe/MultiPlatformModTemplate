package buildconventions

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.withGroovyBuilder

/** 产品 jar 里 snakeyaml 的 relocate 目标（与其余平台车道统一约定，防与宿主冲突）。 */
private const val BUKKIT_SNAKEYAML_PACKAGE = "org.yaml.snakeyaml"
private const val BUKKIT_SNAKEYAML_RELOCATE_TARGET = "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml"

/** 产品/验收 jar 均剔除的 Maven 元数据目录（relocate 不改写其原始坐标，留着只是噪声）。 */
private const val BUKKIT_MAVEN_METADATA_EXCLUSION = "META-INF/maven/**"

/** 产品/验收产物名（发布 jar 名不可变）。 */
internal const val BUKKIT_PRODUCT_JAR_BASE_NAME_PREFIX = "mpmt-bukkit"
private const val BUKKIT_ACCEPTANCE_JAR_BASE_NAME_PREFIX = "mpmt-bukkit-acceptance"

/** 产品 jar 的运行期配置（四条车道同源）。 */
private const val BUKKIT_PRODUCT_RUNTIME_CONFIGURATION = "runtimeClasspath"

/** 验收 jar 随产品并入的 L4 适配器与服务声明（原车道 include 清单）。 */
private val BUKKIT_ACCEPTANCE_JAR_PRODUCT_INCLUDES =
    listOf(
        "top/wcpe/mc/mpmt/platform/bukkit/version/**",
        "META-INF/services/top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter",
    )

/** 产品与验收 jar 都不 shade 的宿主提供依赖（编译期坐标，运行期由服务端提供）。 */
private val BUKKIT_HOST_PROVIDED_API_COORDINATES =
    listOf("io.papermc.paper:paper-api", "org.spigotmc:spigot-api")

/** 产品 jar 任务名（同时是校验/门禁任务的依赖点）。 */
internal const val BUKKIT_PRODUCT_JAR_TASK = "shadowJar"

/** 验收驱动 jar 任务名（发布与门禁任务名不可变）。 */
internal const val BUKKIT_ACCEPTANCE_JAR_TASK = "acceptanceJar"

/**
 * 打包链路：`jar`（仅本模块类，`-plain` 分类器）→ `shadowJar`（+核心+snakeyaml，relocate）→
 * `acceptanceJar`（验收源集 + 产品 L4 适配器）。
 *
 * 分类器与产物名与原车道逐字一致；两个 ShadowJar 任务都保持"每次重跑、不参与构建缓存"，
 * 因为 shadow 不把 relocate/exclude 等配置纳入增量指纹。
 */
internal fun configureBukkitArchiveTasks(project: Project, lane: BukkitLaneExtension, acceptance: SourceSet) {
    val mcVersion = lane.mcVersion.get()
    project.extensions.getByType(BasePluginExtension::class.java)
        .archivesName.set("$BUKKIT_PRODUCT_JAR_BASE_NAME_PREFIX-$mcVersion")
    project.tasks.named("jar", Jar::class.java).configure { archiveClassifier.set("plain") }
    configureBukkitProductArchive(project)
    registerBukkitAcceptanceArchive(project, mcVersion, acceptance)
}

/** 产品 jar：并入运行期类路径、relocate 第三方包、剔除宿主提供的 API。 */
private fun configureBukkitProductArchive(project: Project) {
    val shadowJar = project.tasks.named(BUKKIT_PRODUCT_JAR_TASK, Jar::class.java)
    shadowJar.configure {
        archiveClassifier.set("")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        exclude(BUKKIT_MAVEN_METADATA_EXCLUSION)
        forceRerun()
    }
    configureBukkitShadowTask(
        task = shadowJar.get(),
        configurations = listOf(project.configurations.getByName(BUKKIT_PRODUCT_RUNTIME_CONFIGURATION)),
        relocateSnakeyaml = true,
    )
}

/** 验收 jar：验收源集 + 产品 L4 适配器与服务声明，运行期依赖走验收运行期配置。 */
private fun registerBukkitAcceptanceArchive(project: Project, mcVersion: String, acceptance: SourceSet) {
    val main = bukkitMainSourceSet(project)
    val archive =
        project.tasks.register(BUKKIT_ACCEPTANCE_JAR_TASK, bukkitShadowJarTaskClass(project)) {
            group = "build"
            description = "构建 MC $mcVersion 的 Bukkit realserver 验收插件"
            archiveBaseName.set("$BUKKIT_ACCEPTANCE_JAR_BASE_NAME_PREFIX-$mcVersion")
            archiveClassifier.set("")
            from(acceptance.output)
            from(main.output) { BUKKIT_ACCEPTANCE_JAR_PRODUCT_INCLUDES.forEach { include(it) } }
            exclude(BUKKIT_MAVEN_METADATA_EXCLUSION)
            forceRerun()
        }
    configureBukkitShadowTask(
        task = archive.get(),
        configurations = listOf(project.configurations.getByName(acceptance.runtimeClasspathConfigurationName)),
        relocateSnakeyaml = false,
    )
}

/**
 * shadow 专有接线（`configurations` / `relocate` / 依赖过滤）经 Groovy 动态调用：
 * 插件工程不编译依赖 shadow，故不静态引用其类型；调用顺序与原车道逐项一致。
 */
private fun configureBukkitShadowTask(
    task: Task,
    configurations: List<Configuration>,
    relocateSnakeyaml: Boolean,
) {
    task.withGroovyBuilder {
        setProperty("configurations", configurations)
        if (relocateSnakeyaml) {
            "relocate"(BUKKIT_SNAKEYAML_PACKAGE, BUKKIT_SNAKEYAML_RELOCATE_TARGET)
        }
        "dependencies" {
            BUKKIT_HOST_PROVIDED_API_COORDINATES.forEach { coordinate -> "exclude"("dependency"(coordinate)) }
        }
    }
}

/** 打包任务每次重跑、不参与构建缓存（shadow 的增量指纹不含 relocate/exclude 配置）。 */
private fun Task.forceRerun() {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

/**
 * shadow 任务类型：插件工程不编译依赖 shadow，故取既有 `shadowJar` 任务的未装饰类型
 * （装饰类的父类即原始 `ShadowJar`，注册时由 Gradle 实例化该类型）。
 */
private fun bukkitShadowJarTaskClass(project: Project): Class<Jar> {
    val decorated = project.tasks.named(BUKKIT_PRODUCT_JAR_TASK, Jar::class.java).get().javaClass
    @Suppress("UNCHECKED_CAST")
    return decorated.superclass as Class<Jar>
}
