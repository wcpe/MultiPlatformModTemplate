package buildconventions

import org.gradle.api.Project
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar

/** 产品 jar 里 snakeyaml 的 relocate 目标（ADR-0012：第三方统一 relocate 到 libs.*，防与宿主冲突）。 */
private const val FORGE_SNAKEYAML_PACKAGE = "org.yaml.snakeyaml"
private const val FORGE_SNAKEYAML_RELOCATE_TARGET = "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml"

/** 产品/验收 jar 均剔除的 Maven 元数据目录（relocate 不改写其原始坐标，留着只是噪声）。 */
internal const val FORGE_MAVEN_METADATA_EXCLUSION = "META-INF/maven/**"

/** 自包含产品任务名（shadow；loom 车道 remapJar 的输入）。 */
private const val FORGE_SHADOW_JAR_TASK = "shadowJar"

/** 产品 remap 任务名（loom；承担 FG reobf 的生产命名）。 */
private const val FORGE_REMAP_JAR_TASK = "remapJar"

/**
 * shadow 打包链路（1.20.1 车道）：`jar`（薄包）→ `shadowJar`（shade 共享核心 + relocate snakeyaml）→
 * `remapJar`（named → SRG，吃 shadowJar 产物）。
 *
 * shadow 的类型不在插件工程编译类路径内，`configurations` / `relocate` 经 [invokeGroovy] 动态表达；
 * 调用顺序与迁移前车道脚本逐项一致。shadow 改配置不刷新缓存指纹，故链路保持"每次重跑、不参与构建缓存"。
 */
fun configureForgeShadowProductChain(
    project: Project,
    shadowBundle: String,
    mixinConfigs: String,
) {
    val shadowJar = project.tasks.named(FORGE_SHADOW_JAR_TASK, Jar::class.java)
    shadowJar.configure {
        archiveClassifier.set("dev-shadow")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        invokeGroovy("setConfigurations", listOf(project.configurations.getByName(shadowBundle)))
        invokeGroovy("relocate", FORGE_SNAKEYAML_PACKAGE, FORGE_SNAKEYAML_RELOCATE_TARGET)
        exclude(FORGE_MAVEN_METADATA_EXCLUSION)
        manifest { attributes(mapOf("MixinConfigs" to mixinConfigs)) }
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    val remapJar = project.tasks.named(FORGE_REMAP_JAR_TASK, AbstractArchiveTask::class.java)
    remapJar.configure {
        dependsOn(shadowJar)
        // `inputFile` 是 loom 的 Property：经 Groovy 动态设值（等价车道脚本的 inputFile.set(...)）
        invokeGroovy("setInputFile", shadowJar.flatMap { it.archiveFile })
        archiveClassifier.set("")
    }
}
