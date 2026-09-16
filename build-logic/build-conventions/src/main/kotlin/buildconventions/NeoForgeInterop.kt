package buildconventions

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withGroovyBuilder

/**
 * 插件工程编译类路径之外的外部插件类型：动态互操作。
 *
 * loom 与 shadow 的类型不在本插件工程的编译期依赖里（它们由消费车道自带），故相关配置只能经
 * Groovy 动态派发表达：loom 的 `runs` 容器按名取回 run 设定、shadow 的 `configurations` / `relocate`
 * 与 loom `remapJar` 的 `inputFile` 都走对象自身的真实方法（属性名当方法调用对 Gradle 装饰对象不成立，
 * 见 `build-conventions` 探针结论），取值口径与迁移前的车道脚本逐项一致。
 */

/** shadow 插件扩展名：只用于取 shadow 插件的类加载器（其类型不在插件工程编译类路径内）。 */
internal const val NEOFORGE_SHADOW_EXTENSION: String = "shadow"

/** shadow 插件自包含 jar 任务类型全名。 */
internal const val NEOFORGE_SHADOW_JAR_CLASS: String = "com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar"

/** loom 扩展名。 */
private const val NEOFORGE_LOOM_EXTENSION: String = "loom"

/** 动态调用并取回返回值（Groovy 动态派发）：共享的 [invokeGroovy] 不返回结果，取回对象时用本函数。 */
internal fun Any.groovyValue(name: String, vararg args: Any?): Any? = withGroovyBuilder { name.invoke(*args) }

/** loom 的 `runs` 容器（`loom.runs`）：forge 系由 loom 预建 client / server 运行配置。 */
internal fun neoforgeLoomRuns(project: Project): NamedDomainObjectContainer<*> {
    val runs = project.extensions.getByName(NEOFORGE_LOOM_EXTENSION).withGroovyBuilder { getProperty("runs") }
    return runs as? NamedDomainObjectContainer<*> ?: error("loom 扩展未提供 runs 容器")
}

/** 按名取回 loom 预建的 run 设定；缺失即失败（车道顺序或平台选型不符时尽早暴露）。 */
internal fun neoforgeRunConfig(runs: NamedDomainObjectContainer<*>, name: String): Any =
    runs.getByName(name) ?: error("loom 未预建 run 配置：$name")
