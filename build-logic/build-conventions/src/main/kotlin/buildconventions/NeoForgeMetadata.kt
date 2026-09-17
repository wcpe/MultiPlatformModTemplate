package buildconventions

import org.gradle.api.Project
import org.gradle.language.jvm.tasks.ProcessResources

/**
 * mod 元数据资源名：1.20.2 仍用 `META-INF/mods.toml`（`neoforge.mods.toml` 重命名是 20.5+），
 * 产品 mod 与验收驱动 mod 同名同类，故共用本常量。
 */
private const val NEOFORGE_MOD_METADATA: String = "META-INF/mods.toml"

/** 产品 mod 元数据的 `${version}` 占位由构建注入。 */
internal fun expandNeoForgeModMetadata(project: Project) {
    // 展开用版本在配置期取成字符串：copy-spec 闭包只捕获它，不捕获 project（配置缓存要求）。
    val injectedVersion = project.version.toString()
    project.tasks.named("processResources", ProcessResources::class.java).configure {
        inputs.property("version", injectedVersion)
        filesMatching(NEOFORGE_MOD_METADATA) { expand(mapOf("version" to injectedVersion)) }
    }
}

/** 验收驱动 mod 元数据的 `${version}` 占位由构建注入。 */
internal fun expandNeoForgeAcceptanceModMetadata(project: Project) {
    val injectedVersion = project.version.toString()
    project.tasks.named("processAcceptanceResources", ProcessResources::class.java).configure {
        inputs.property("version", injectedVersion)
        filesMatching(NEOFORGE_MOD_METADATA) { expand(mapOf("version" to injectedVersion)) }
    }
}
