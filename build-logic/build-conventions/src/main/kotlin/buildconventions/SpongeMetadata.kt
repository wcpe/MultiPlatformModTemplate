package buildconventions

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.language.jvm.tasks.ProcessResources

/** SpongeGradle 扩展名。 */
private const val SPONGE_EXTENSION: String = "sponge"

/** SpongeGradle 元数据任务名。 */
private const val WRITE_METADATA_TASK: String = "writePluginMetadata"

/** SpongeGradle 元数据生成目录（`build/generated/sponge/plugin`）。 */
private const val METADATA_DIRECTORY: String = "generated/sponge/plugin"

/** 元数据声明的 SpongeAPI 依赖 id。 */
private const val SPONGE_API_DEPENDENCY: String = "spongeapi"

/** 依赖加载顺序枚举全名（SpongeGradle 类型不在插件工程编译类路径内）。 */
private const val LOAD_ORDER_CLASS: String =
    "org.spongepowered.plugin.metadata.model.PluginDependency\$LoadOrder"

/** 依赖加载顺序常量名：与原车道脚本 `PluginDependency.LoadOrder.AFTER` 一致。 */
private const val LOAD_ORDER_AFTER: String = "AFTER"

/** SpongeGradle 元数据生成目录。 */
internal fun spongeMetadataDirectory(project: Project): Provider<Directory> =
    project.layout.buildDirectory.dir(METADATA_DIRECTORY)

/**
 * SpongeGradle 元数据装配：`sponge {}` 取值全部由车道参数派生（取值口径与迁移前逐项一致），
 * 生成目录并入 `processResources`，并显式接线 `writePluginMetadata`。
 *
 * 车道对 main 资源目录 `setSrcDirs(...)` 会覆盖 SpongeGradle 自动挂上的生成目录，故必须重新挂一次；
 * `from(路径)` 只是路径 Provider、不带任务依赖，故必须显式 dependsOn——否则干净克隆
 * （无历史构建产物）下必缺 `META-INF/sponge_plugins.json`。
 *
 * `spongeLane { … }` 参数块晚于插件 apply，故本函数须在 afterEvaluate 调用。
 */
internal fun configureSpongeMetadata(project: Project, lane: SpongeLaneExtension) {
    configureSpongeExtension(project, lane)
    project.tasks.named("processResources", ProcessResources::class.java).configure {
        dependsOn(WRITE_METADATA_TASK)
        from(spongeMetadataDirectory(project))
    }
    pinSpongeApiCompileVersion(project, lane)
}

/**
 * 配置 SpongeGradle 的 `sponge {}` 扩展：每条调用对应迁移前车道脚本里的一行 DSL，取值逐项相同。
 *
 * SpongeGradle 类型不在插件工程编译类路径内，故经 Groovy 动态调用表达同义配置。
 */
private fun configureSpongeExtension(project: Project, lane: SpongeLaneExtension) {
    val sponge = project.extensions.getByName(SPONGE_EXTENSION)
    sponge.invokeGroovy("apiVersion", lane.spongeApiMetadataVersion.get())
    sponge.invokeGroovy("license", lane.license.get())
    sponge.invokeGroovy(
        "loader",
        groovyAction { loader ->
            loader.invokeGroovy("name", lane.loaderName.get())
            loader.invokeGroovy("version", lane.loaderVersion.get())
        },
    )
    sponge.invokeGroovy(
        "plugin",
        lane.pluginId.get(),
        groovyAction { plugin ->
            plugin.invokeGroovy("displayName", lane.displayName.get())
            plugin.invokeGroovy("entrypoint", lane.entrypoint.get())
            plugin.invokeGroovy("description", lane.description.get())
            spongeApiDependency(plugin)
        },
    )
}

/** 元数据的 spongeapi 依赖：加载顺序 AFTER、非可选（与原脚本逐项一致）。 */
private fun spongeApiDependency(plugin: Any) {
    plugin.invokeGroovy(
        "dependency",
        SPONGE_API_DEPENDENCY,
        groovyAction { dependency ->
            dependency.invokeGroovy(
                "loadOrder",
                enumConstantOf(dependency, LOAD_ORDER_CLASS, LOAD_ORDER_AFTER),
            )
            dependency.invokeGroovy("optional", false)
        },
    )
}

/**
 * SpongeGradle 仍以元数据版本声明 spongeapi：只把**精确匹配**该声明版本的编译依赖固定到 RC1365 同源制品。
 */
private fun pinSpongeApiCompileVersion(project: Project, lane: SpongeLaneExtension) {
    val declaredVersion = lane.spongeApiMetadataVersion.get()
    val compileVersion = lane.spongeApiCompileVersion.get()
    project.configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (
                requested.group == "org.spongepowered" &&
                requested.name == SPONGE_API_DEPENDENCY &&
                requested.version == declaredVersion
            ) {
                useVersion(compileVersion)
            }
        }
    }
}
