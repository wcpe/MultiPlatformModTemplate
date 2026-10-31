package buildconventions

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask

/**
 * 跨车道产品 jar 的产物依赖通道：生产者侧暴露的可消费配置名（跨车道统一契约）。
 *
 * <p>同名配置在各车道工程里各自独立（配置名只在所属工程内唯一），消费方按「工程路径 + 本名」取用。
 */
const val PRODUCT_JAR_VARIANT_CONFIGURATION = "productJar"

/**
 * 生产者侧：把产品 jar 暴露为可消费配置（outgoing variant）。
 *
 * <p>产物文件与其打包任务一并随配置发布，因此消费方只需声明产物依赖，
 * 「先构建产品 jar」的任务顺序由 Gradle 从产物依赖自行推导——消费方既不必写死
 * `build/libs/…` 路径（车道改名 / 产物改命名时不会在编译期暴露问题），
 * 也不必手写 `dependsOn(":platform:xxx:shadowJar")` 这类与打包链路脱钩的任务边。
 *
 * @param productJar 产品 jar 的打包任务（如 `shadowJar` / `remapJar`）。
 * @param configurationName 暴露给消费方的配置名，默认 [PRODUCT_JAR_VARIANT_CONFIGURATION]。
 */
fun Project.exposeProductJarVariant(
    productJar: TaskProvider<out AbstractArchiveTask>,
    configurationName: String = PRODUCT_JAR_VARIANT_CONFIGURATION,
): Configuration {
    val variant =
        configurations.maybeCreate(configurationName).apply {
            // 只发布不解析：消费者通过项目依赖请求本配置
            isCanBeConsumed = true
            isCanBeResolved = false
            description = "产品 jar（${productJar.name} 产物）：供其它工程以产物依赖消费，任务顺序由 Gradle 推导"
        }
    // 幂等：约定插件与车道脚本重复暴露时不会重复追加产物（多产物会让消费方的单值取值失败）。
    if (variant.outgoing.artifacts.isEmpty()) {
        variant.outgoing.artifact(productJar)
    }
    return variant
}

/**
 * 消费方拿到的产物依赖视图：文件集合与单值文件都携带上游产物的构建依赖。
 *
 * <p>[files] 适合 `dependsOn(…)` 或任务输入；[file] 适合直接 `set` 到
 * [org.gradle.api.file.RegularFileProperty]。二者都经配置的 `elements` 派生，
 * 因此 Gradle 仍能沿产物依赖把上游打包任务排进任务图。
 */
class ProductJarDependency internal constructor(
    /** 产品 jar 文件集合（惰性，携带上游构建依赖）。 */
    val files: FileCollection,
    /** 产品 jar 单值文件（惰性，携带上游构建依赖）。 */
    val file: Provider<RegularFile>,
)

/**
 * 消费方：声明对指定车道产品 jar 的产物依赖。
 *
 * <p>与生产者侧的 [exposeProductJarVariant] 配对使用；只取产物本体、不传播其依赖
 *（`isTransitive = false`），避免把上游车道编译期依赖混进本工程的用途。
 *
 * @param producerProjectPath 生产者工程路径（如 `:platform:fabric:fabric-26.2`）。
 * @param consumerConfigurationName 本消费方用于解析产物的可解析配置名（工程内唯一即可）。
 * @param producerConfigurationName 生产者暴露的配置名，默认 [PRODUCT_JAR_VARIANT_CONFIGURATION]。
 */
fun Project.consumeProductJar(
    producerProjectPath: String,
    consumerConfigurationName: String,
    producerConfigurationName: String = PRODUCT_JAR_VARIANT_CONFIGURATION,
): ProductJarDependency {
    val resolvable =
        configurations.maybeCreate(consumerConfigurationName).apply {
            isCanBeConsumed = false
            isCanBeResolved = true
            description = "跨车道产品 jar 产物依赖：$producerProjectPath（$producerConfigurationName）"
        }
    dependencies.add(
        resolvable.name,
        (dependencies.project(mapOf("path" to producerProjectPath, "configuration" to producerConfigurationName)) as ProjectDependency)
            // 只要产物本体：带上游车道的传递依赖会把无关制品混进消费方
            .apply { isTransitive = false },
    )
    // elements 派生的 Provider 保留产物的构建依赖，故单值文件同样能让读取它的任务排在上游打包任务之后。
    return ProductJarDependency(resolvable, layout.file(resolvable.elements.map { it.single().asFile }))
}
