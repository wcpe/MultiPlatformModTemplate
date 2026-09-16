package buildconventions

import org.gradle.api.Project
import org.gradle.language.jvm.tasks.ProcessResources

/** `plugin.yml` 展开目标文件。 */
private const val BUKKIT_PLUGIN_DESCRIPTOR = "plugin.yml"

/** 产品/验收 `plugin.yml` 占位符键：与模板中的 `${…}` 逐字对应，不得改名。 */
private const val BUKKIT_METADATA_VERSION_KEY = "version"
private const val BUKKIT_METADATA_API_VERSION_KEY = "apiVersionMetadata"
private const val BUKKIT_METADATA_FOLIA_KEY = "foliaMetadata"

/**
 * `plugin.yml` 占位展开：产品源集与验收源集用同一份元数据。
 *
 * 键名与取值口径与原各车道内联实现逐字一致（空串即不注入该行），
 * 故产物 `plugin.yml` 的字节不因本次抽取而改变。
 */
internal fun expandBukkitPluginMetadata(project: Project, lane: BukkitLaneExtension) {
    val metadata = bukkitPluginMetadata(project, lane)
    listOf("processResources", bukkitAcceptanceSourceSet(project).processResourcesTaskName)
        .forEach { taskName -> expandBukkitMetadataInto(project, taskName, metadata) }
}

/** 元数据 map：`version` 取工程版本，`api-version` 与 `folia-supported` 由车道参数派生。 */
private fun bukkitPluginMetadata(project: Project, lane: BukkitLaneExtension): Map<String, Any> {
    val apiVersion = lane.apiVersion.get()
    return mapOf(
        BUKKIT_METADATA_VERSION_KEY to project.version,
        BUKKIT_METADATA_API_VERSION_KEY to if (apiVersion.isEmpty()) "" else "api-version: '$apiVersion'",
        BUKKIT_METADATA_FOLIA_KEY to if (lane.foliaSupported.get()) "folia-supported: true" else "",
    )
}

/** 登记展开输入并执行展开（输入参与增量构建指纹，故与 `expand` 同源同表）。 */
private fun expandBukkitMetadataInto(project: Project, taskName: String, metadata: Map<String, Any>) {
    project.tasks.named(taskName, ProcessResources::class.java).configure {
        inputs.properties(metadata)
        filesMatching(BUKKIT_PLUGIN_DESCRIPTOR) { expand(metadata) }
    }
}
